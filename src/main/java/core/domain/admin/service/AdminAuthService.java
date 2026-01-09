package core.domain.admin.service;

import core.domain.user.dto.AdminLoginStep1Response;
import core.domain.user.dto.OtpVerificationRequest;
import core.domain.user.entity.AdminOtp;
import core.domain.user.entity.User;
import core.domain.user.repository.AdminOtpRepository;
import core.domain.user.repository.UserRepository;
import core.domain.user.service.GoogleOtpService;
import core.global.dto.AuthResponse;
import core.global.dto.EmailLoginDto;
import core.global.dto.UserLoggedInEvent;
import core.global.enums.Ouathplatform;
import core.global.enums.Role;
import core.global.enums.errorcode.AuthErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import core.global.redis.service.RedisService;
import core.global.security.JwtTokenProvider;
import core.global.service.SmtpMailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AdminAuthService {

    private final UserRepository userRepository;
    private final AdminOtpRepository adminOtpRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final GoogleOtpService googleOtpService;
    private final RedisService redisService;
    private final ApplicationEventPublisher publisher;
    private final SmtpMailService smtpMailService;

    private static final String OTP_RESET_PREFIX = "admin:otp-reset:";
    private static final long RESET_CODE_TTL = 5L;

    public AdminLoginStep1Response loginStep1(EmailLoginDto req) {
        User user = userRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> {
                    log.warn("[ADMIN LOGIN Step1] 사용자 없음: email={}", req.getEmail());
                    return new BusinessException(UserErrorCode.AUTHENTICATION_FAILED);
                });

        if (!Ouathplatform.local.toString().equalsIgnoreCase(user.getProvider())) {
            log.warn("[ADMIN LOGIN Step1] Provider 불일치: {}", user.getProvider());
            throw new BusinessException(UserErrorCode.AUTHENTICATION_FAILED);
        }

        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            log.warn("[ADMIN LOGIN Step1] 비밀번호 불일치");
            throw new BusinessException(UserErrorCode.AUTHENTICATION_FAILED);
        }

        if (user.getUserRole() != Role.ADMIN) {
            log.warn("[ADMIN LOGIN Step1] 관리자 권한 없음");
            throw new BusinessException(AuthErrorCode.AUTHENTICATION_ADMIN_FAILED);
        }

        Optional<AdminOtp> otpOp = adminOtpRepository.findByUser(user);
        String qrUrl = null;

        if (otpOp.isEmpty()) {
            var key = googleOtpService.generateSecretKey();
            adminOtpRepository.save(AdminOtp.builder().user(user).secretKey(key.getKey()).build());
            qrUrl = googleOtpService.getGoogleAuthenticatorBarCode(key.getKey(), user.getEmail());
        }

        String tempToken = jwtTokenProvider.createAccessToken(user.getId(), "PRE_AUTH_ADMIN", user.getEmail());

        return AdminLoginStep1Response.builder()
                .requiresOtp(true)
                .tempToken(tempToken)
                .qrCodeUrl(qrUrl)
                .build();
    }

    public AuthResponse loginStep2(OtpVerificationRequest req) {
        if (!jwtTokenProvider.validateToken(req.getTempToken())) {
            throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
        }
        if (!"PRE_AUTH_ADMIN".equals(jwtTokenProvider.getRoleFromToken(req.getTempToken()))) {
            throw new BusinessException(AuthErrorCode.INVALID_AUTH_STEP);
        }

        User user = userRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        AdminOtp adminOtp = adminOtpRepository.findByUser(user)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTHENTICATION_ADMIN_FAILED));

        if (!googleOtpService.verifyCode(adminOtp.getSecretKey(), req.getOtpCode())) {
            log.warn("[ADMIN LOGIN Step2] OTP 불일치: email={}", user.getEmail());
            throw new BusinessException(AuthErrorCode.INVALID_OTP);
        }

        adminOtp.useToken();

        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), Role.ADMIN.name(), user.getEmail());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId());

        long ttlMs = jwtTokenProvider.getExpiration(refreshToken).getTime() - System.currentTimeMillis();
        redisService.saveRefreshToken(user.getId(), refreshToken, ttlMs);

        publisher.publishEvent(new UserLoggedInEvent(user.getId().toString(), "email"));
        log.info("[ADMIN LOGIN Success] 관리자 로그인 완료: id={}", user.getId());

        long accessExpiresIn = jwtTokenProvider.getExpiration(accessToken).getTime() - System.currentTimeMillis();
        return new AuthResponse("Bearer", accessToken, refreshToken, accessExpiresIn, user.getId(), user.getEmail(), false);
    }

    public void sendOtpResetCode(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        if (user.getUserRole() != Role.ADMIN) {
            throw new BusinessException(AuthErrorCode.AUTHENTICATION_ADMIN_FAILED);
        }

        if (adminOtpRepository.findByUser(user).isEmpty()) {
            throw new BusinessException(AuthErrorCode.OTP_NOT_REGISTERED);
        }

        String code = String.valueOf((int)(Math.random() * 900000) + 100000);

        redisService.setDataExpire(OTP_RESET_PREFIX + email, code, Duration.ofMinutes(RESET_CODE_TTL).toMillis());

        smtpMailService.sendAdminOtpResetEmail(email, code, Duration.ofMinutes(RESET_CODE_TTL));
    }

    public void resetOtp(String email, String code) {
        String savedCode = redisService.getData(OTP_RESET_PREFIX + email);

        if (savedCode == null) {
            throw new BusinessException(AuthErrorCode.VERIFY_CODE_EXPIRES);
        }
        if (!savedCode.equals(code)) {
            throw new BusinessException(AuthErrorCode.VERIFY_CODE_NOT_MATCH);
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        AdminOtp adminOtp = adminOtpRepository.findByUser(user)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.OTP_NOT_REGISTERED));

        adminOtpRepository.delete(adminOtp);
        redisService.deleteData(OTP_RESET_PREFIX + email);
        log.info("[Admin OTP Reset] 관리자 OTP 초기화 완료: email={}", email);
    }
}

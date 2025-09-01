package core.global.service;

import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.dto.ResetToken;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordService {

    private static final String RESET_TOKEN_KEY   = "PWRESET:TOKEN:";    // token -> email
    private static final String RESET_EMAIL_LOCK  = "PWRESET:EMAIL:";    // email -> last token
    private static final String RESET_SESSION_KEY = "PWRESET:SESSION:";  // sid   -> email

    private final UserRepository userRepository;
    private final StringRedisTemplate redis;
    private final PasswordEncoder passwordEncoder;
    private final SmtpMailService smtpMailService;

    /**
     * 토큰 , 세션 ,기본 baseurl
     */
    @Value("${app.password.reset.ttl-minutes}")
    private long resetTtlMin;

    @Value("${app.password.reset.session-ttl-minutes}")
    private long sessionTtlMin;

    @Value("${app.api.base-url}")
    private String apiBaseUrl;

    /*
     * 세션ID 방식 메일 전송
     * ========================= */
    public void sendResetMailSessionMode(String rawEmail, @Nullable Locale locale) {
        final String email = normalizeEmail(rawEmail);
        final Locale loc = (locale != null) ? locale : LocaleContextHolder.getLocale();

        userRepository.findByEmail(email).ifPresent(user -> {
            String sid = UUID.randomUUID().toString();
            Duration sessionTtl = Duration.ofMinutes(sessionTtlMin);
            redis.opsForValue().set(RESET_SESSION_KEY + sid, email, sessionTtl);

            String startUrl = apiBaseUrl + "/api/v1/member/password/start-reset?sid=" + sid;

            smtpMailService.sendPasswordResetSessionEmail(email, sessionTtl, loc, startUrl);
        });
    }

    /*
     * 토큰 생성(세션ID 소비 시)
     *
     */
    public ResetToken issueTokenFromSession(String sessionId) {
        String key = RESET_SESSION_KEY + sessionId;
        String email = redis.opsForValue().get(key);
        if (email == null) {
            throw new BusinessException(ErrorCode.INVALID_OR_EXPIRED_SESSION);
        }

        // 세션ID는 1회용 → 즉시 삭제
        redis.delete(key);

        // 실제 토큰 생성 및 저장
        String token = generateToken();
        Duration ttl = Duration.ofMinutes(resetTtlMin);
        redis.opsForValue().set(RESET_TOKEN_KEY + token, email, ttl);

        return new ResetToken(token, ttl.toSeconds());
    }
    // PasswordService 내부에 추가
    @Transactional
    public void validateTokenAndResetPassword(String token, String newPassword) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_OR_EXPIRED_TOKEN);
        }

        final String tokenKey = RESET_TOKEN_KEY + token; // "PWRESET:TOKEN:" + token
        String email = redis.opsForValue().get(tokenKey);
        if (email == null) {
            throw new BusinessException(ErrorCode.INVALID_OR_EXPIRED_TOKEN);
        }

        assertStrongPassword(newPassword);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        user.setPassword(passwordEncoder.encode(newPassword));

        redis.delete(tokenKey);
        redis.delete(RESET_EMAIL_LOCK + email);
    }

    /* =========================
     * 토큰 검증/비밀번호 변경
     * ========================= */
    public boolean isValidResetToken(String token) {
        return token != null && redis.hasKey(RESET_TOKEN_KEY + token);
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_OR_EXPIRED_TOKEN);
        }
        String email = redis.opsForValue().get(RESET_TOKEN_KEY + token);
        if (email == null) {
            throw new BusinessException(ErrorCode.INVALID_OR_EXPIRED_TOKEN);
        }

        assertStrongPassword(newPassword);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        user.setPassword(passwordEncoder.encode(newPassword));

        /*
         토큰 1회성 사용 → 즉시 삭제
         */
        redis.delete(RESET_TOKEN_KEY + token);
        redis.delete(RESET_EMAIL_LOCK + email);
    }

    /* =========================
     * 유틸
     * ========================= */
    private String normalizeEmail(String raw) {
        return raw == null ? null : raw.trim().toLowerCase();
    }

    /** URL-safe 랜덤 토큰 */
    private String generateToken() {
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private void assertStrongPassword(String pw) {
        if (pw == null || pw.length() < 8) {
            throw new BusinessException(ErrorCode.WEAK_PASSWORD);
        }
    }

}

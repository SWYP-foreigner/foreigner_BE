package core.global.service;

import core.domain.user.entity.User;
import core.domain.user.service.UserService;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import core.global.security.JwtTokenProvider;
import core.global.dto.AccessTokenDto;
import core.global.dto.GoogleProfileDto;
import core.global.dto.LoginResponseDto;
import core.global.dto.UserLoggedInEvent;
import core.global.enums.Oauthplatform;
import core.global.redis.service.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleAuthService {

    private final GoogleService googleService;
    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;
    private final RedisService redisService;
    private final ApplicationEventPublisher publisher;

    @Transactional
    public LoginResponseDto processGoogleLogin(String authCode) {
        log.info("==================================================");
        log.info(">>> [Google Login] 프로세스 시작");

        // 1. Auth Code 로깅 (보안상 앞 10자리만, null 체크)
        String codeSnippet = (authCode != null && authCode.length() > 10) ? authCode.substring(0, 10) + "..." : authCode;
        log.info(">>> [Step 1] 인증 코드 수신 확인: {}", codeSnippet);

        if (authCode == null || authCode.isBlank()) {
            log.error("<<< [실패] 인증 코드가 비어있습니다.");
            throw new BusinessException(UserErrorCode.INVALID_INPUT_VALUE); // 적절한 에러코드로 변경 필요
        }

        // 2. 구글 액세스 토큰 교환
        AccessTokenDto accessTokenDto;
        try {
            log.info("    -> 구글로 토큰 교환 요청 전송...");
            accessTokenDto = googleService.exchangeCode(authCode);
            log.info("    -> 토큰 교환 성공. AccessToken(일부): {}...",
                    (accessTokenDto != null && accessTokenDto.getAccess_token() != null)
                            ? accessTokenDto.getAccess_token().substring(0, 5) : "NULL");
        } catch (Exception e) {
            log.error("<<< [실패] 구글 토큰 교환 중 에러 발생: {}", e.getMessage());
            throw e; // 에러 다시 던짐
        }

        // 3. 구글 프로필 조회
        GoogleProfileDto profile;
        try {
            log.info(">>> [Step 2] 구글 프로필 정보 요청");
            profile = googleService.getGoogleProfile(accessTokenDto.getAccess_token());
            log.info("    -> 프로필 수신 완료: Email={}, Sub(ID)={}", profile.getEmail(), profile.getSub());
        } catch (Exception e) {
            log.error("<<< [실패] 구글 프로필 조회 중 에러 발생: {}", e.getMessage());
            throw e;
        }

        // 4. 유저 조회 또는 생성 (핵심 로직)
        User user;
        try {
            log.info(">>> [Step 3] 유저 DB 조회/생성 로직 진입");
            user = findOrCreateUser(profile);
            log.info("    -> 유저 확정 완료: UserID={}, Email={}, Role={}", user.getId(), user.getEmail(), user.getUserRole());
        } catch (BusinessException e) {
            log.warn("<<< [실패] 비즈니스 로직 에러(이메일 중복 등): {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("<<< [실패] 유저 DB 처리 중 알 수 없는 에러: {}", e.getMessage());
            throw e;
        }

        // 5. JWT 발급
        log.info(">>> [Step 4] 자체 JWT 토큰 발급 시작");
        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getUserRole().toString(), user.getEmail());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId());

        Date expirationDate = jwtTokenProvider.getExpiration(refreshToken);
        long expirationMillis = expirationDate.getTime() - System.currentTimeMillis();

        redisService.saveRefreshToken(user.getId(), refreshToken, expirationMillis);
        log.info("    -> Redis 저장 완료. RefreshToken 만료: {}ms", expirationMillis);

        boolean isNewUserResponse = user.isNewUser();
        publisher.publishEvent(new UserLoggedInEvent(user.getId().toString(), "google"));

        log.info("<<< [Google Login] 로그인 성공. 응답 반환.");
        log.info("==================================================");

        return new LoginResponseDto(user.getId(), accessToken, refreshToken, isNewUserResponse);
    }

    private User findOrCreateUser(GoogleProfileDto profile) {
        // A. 이미 가입된 소셜 유저인지 확인
        User user = userService.getUserBySocialIdAndProvider(profile.getSub(), String.valueOf(Oauthplatform.GOOGLE));
        if (user != null) {
            log.info("    -> [분기] 기존 가입 유저 발견. (UserID: {})", user.getId());
            return user;
        }

        // B. 이메일 중복 체크 (다른 소셜이나 일반 가입으로 이미 있는지)
        if (userService.existsByEmail(profile.getEmail())) {
            log.warn("    -> [분기] 이메일 중복 발생! 기존 가입된 이메일입니다: {}", profile.getEmail());
            // 여기서 로그를 보면 '어떤 이메일이 충돌났는지' 알 수 있음
            throw new BusinessException(UserErrorCode.DUPLICATE_EMAIL_PROVIDER_MISMATCH);
        }

        // C. 신규 가입
        log.info("    -> [분기] 신규 유저 생성 시작. Email: {}", profile.getEmail());
        return userService.createOauth(profile.getSub(), profile.getEmail(), String.valueOf(Oauthplatform.GOOGLE));
    }
}
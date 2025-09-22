package core.global.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.domain.user.service.UserService;
import core.global.config.JwtTokenProvider;
import core.global.dto.*;
import core.global.enums.ErrorCode;
import core.global.enums.Ouathplatform;
import core.global.exception.BusinessException;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.security.PublicKey;
import java.util.*;

/**
 * 애플(iOS 네이티브) 로그인/토큰 교환/연동 해제(Service 계층).
 * - 권장 플로우: App → 서버에 authorizationCode(+ rawNonce) 전달
 * - 서버: client_secret 생성 → /auth/token 교환 → id_token 검증(iss/aud/exp/nonce)
 * - 장기 로그인: refresh_token 서버 보관, 필요 시 /auth/token(Refresh) 호출
 * - 연동 해제: /auth/revoke 로 refresh_token 무효화
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AppleAuthService {


    private final JwtTokenProvider jwtProvider;
    private final RedisService redisService;
    private final UserService userService;
    private final AppleKeyService appleKeyService;
    private final ApplePublicKeyGenerator applePublicKeyGenerator;
    private final JwtTokenProvider jwtTokenProvider;
    private final AppleOAuthProperties appleProps;
    private final AppleClientSecretGenerator appleClientSecretGenerator;
    private final AppleClient appleClient;

    @Value("${oauth.apple.issuer}")
    private String issuer;
    public Claims verifyAndGetClaims(String identityToken, String nonce) {
        log.debug("--- Apple Token Verification Start ---");
        log.debug("Received identityToken (first 30 chars): {}", identityToken != null ? identityToken.substring(0, Math.min(identityToken.length(), 30)) : "null");
        log.debug("Received nonce from request: {}", nonce);

        try {
            log.info("1-1. Apple 공개키 목록 가져오기 시도...");
            ApplePublicKeyResponse publicKeyResponse = appleKeyService.getApplePublicKeys();
            log.info("1-2. Apple 공개키 {}개 수신 완료.", publicKeyResponse.keys().size());

            log.info("2-1. identityToken 헤더 파싱 시도...");
            Map<String, String> headers = jwtProvider.parseHeaders(identityToken);
            String kidFromHeader = headers.get("kid");
            String algFromHeader = headers.get("alg");
            log.info("2-2. 토큰 헤더에서 kid: [{}], alg: [{}] 추출 완료.", kidFromHeader, algFromHeader);

            log.info("2-3. 헤더 정보와 일치하는 공개키 생성 시도...");
            PublicKey publicKey = applePublicKeyGenerator.generate(headers, publicKeyResponse);
            log.info("2-4. 서명 검증용 PublicKey 생성 완료.");

            log.info("3-1. 공개키를 이용한 서명 검증 및 Claims 추출 시도...");
            Claims claims = jwtProvider.getTokenClaims(identityToken, publicKey);
            log.info("3-2. 서명 검증 성공 및 Claims 추출 완료. Subject(sub): {}", claims.getSubject());

            log.info("4-1. Claims 유효성 검증 시작...");
            String expectedIssuer = "https://appleid.apple.com";
            String actualIssuer = claims.getIssuer();
            log.debug("Comparing Issuer -> Expected: [{}], Actual: [{}]", expectedIssuer, actualIssuer);
            if (!expectedIssuer.equals(actualIssuer)) {
                throw new BusinessException(ErrorCode.INVALID_JWT_ISSUER);
            }


            String expectedAudience = appleProps.appBundleId();
            String actualAudience = claims.getAudience();
            log.debug("Comparing Audience -> Expected: [{}], Actual: [{}]", expectedAudience, actualAudience);
            if (!expectedAudience.equals(actualAudience)) {
                throw new BusinessException(ErrorCode.INVALID_JWT_AUDIENCE);
            }

            String nonceFromToken = claims.get("nonce", String.class);
            log.debug("Comparing Nonce -> Expected: [{}], Actual: [{}]", nonce, nonceFromToken);
            if (nonce == null || !nonce.equals(nonceFromToken)) {
                throw new BusinessException(ErrorCode.INVALID_JWT_NONCE);
            }
            log.info("4-2. Claims 유효성 검증 모두 통과.");

            return claims;
        } catch (BusinessException e) {
            log.error("Apple token verification failed with BusinessException: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Apple identityToken 검증 중 예상치 못한 심각한 오류 발생", e);
            throw new BusinessException(ErrorCode.INVALID_JWT_APPLE);
        }
    }

    public LoginResponseDto login(AppleLoginByCodeRequest req) {
        log.info("--- [Apple 앱 로그인] 처리 시작 ---");
        Claims claims = verifyAndGetClaims(req.identityToken(), req.nonce());
        String appleSocialId = claims.getSubject();
        String provider = Ouathplatform.APPLE.toString();

        log.info("2. 데이터베이스에 기존 사용자가 있는지 확인하는 중...");
        User user = userService.getUserBySocialIdAndProvider(appleSocialId, provider);
        if (user == null) {
            log.info("새로운 사용자입니다. Apple 서버로부터 토큰 발급 시도...");
            String appleRefreshToken = requestAppleToken(req.authorizationCode());
            log.info("Apple 서버로부터 refresh_token 수신 완료. 계정 생성 시작...");
            String emailFromToken = claims.get("email", String.class);
            user = userService.createAppleOauth(
                    appleSocialId,
                    emailFromToken,
                    provider,
                    appleRefreshToken,
                    req.fullName()
            );
            log.info("새로운 사용자 계정 생성 완료. 사용자 ID: {}", user.getId());
        } else if (user.isNewUser() && user.getProvider().equals(Ouathplatform.APPLE.toString())) {
            AppleLoginByCodeRequest.FullNameDto fullName = req.fullName();
            if (fullName != null) {
                log.info("기존 사용자 ID {}의 이름 정보 업데이트를 시도합니다.", user.getId());

                boolean needsUpdate = false;
                if (fullName.givenName() != null && !fullName.givenName().isBlank()) {
                    user.updateFirstName(fullName.givenName());
                    log.info("FirstName 업데이트: {}", fullName.givenName());
                    needsUpdate = true;
                }
                if (fullName.familyName() != null && !fullName.familyName().isBlank()) {
                    user.updateLastName(fullName.familyName());
                    log.info("LastName 업데이트: {}", fullName.familyName());
                    needsUpdate = true;
                }


                if (needsUpdate) {
                    userService.updateUser(user,fullName);
                    log.info("사용자 이름 정보 업데이트를 완료했습니다.");
                }
            }
        }
        else {
            log.info("기존 사용자 발견. 사용자 ID: {}", user.getId());
        }
        boolean isNewUserResponse = user.isNewUser();
        log.info("{},{}",user.isNewUser(),user.getProvider());
        log.info("3. 인증된 사용자를 위한 새로운 JWT 토큰을 생성하는 중...");
        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId());

        log.debug("Access Token generated for user ID {}: {}", user.getId(), accessToken);
        log.debug("Refresh Token generated for user ID {}: {}", user.getId(), refreshToken);

        Date expirationDate = jwtTokenProvider.getExpiration(refreshToken);
        long expirationMillis = expirationDate.getTime() - System.currentTimeMillis();
        redisService.saveRefreshToken(user.getId(), refreshToken, expirationMillis);

        return new LoginResponseDto(user.getId(), accessToken, refreshToken, isNewUserResponse);
    }
    /**
     * authorizationCode를 사용해 Apple 서버에 토큰 발급을 요청하고, refresh_token을 반환하는 private 메소드
     */
    private String requestAppleToken(String authorizationCode) {
        String clientSecret = appleClientSecretGenerator.generateClientSecret();

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("client_id", appleProps.appBundleId());
        formData.add("client_secret", clientSecret);
        formData.add("code", authorizationCode);
        formData.add("grant_type", "authorization_code");

        // --- 👇 [디버깅 로그 추가] ---
        log.info("--- Apple /auth/token Request Body ---");
        log.info("client_id: {}", appleProps.appBundleId());
        log.info("grant_type: authorization_code");
        log.info("code (Authorization Code): {}", authorizationCode);
        // 🚨 WARNING: 아래 로그는 매우 민감한 정보이므로, 디버깅 완료 후 반드시 삭제하세요.
        log.info("client_secret (JWT): {}", clientSecret);
        log.info("------------------------------------");
        // --- [디버깅 로그 끝] ---

        try {
            AppleRefreshTokenResponse response = appleClient.getToken(formData);
            return response.refreshToken();
        } catch (Exception e) {
            log.error("Failed to get token from Apple server.", e);
            throw new BusinessException(ErrorCode.INVALID_APPLE_REQUEST);
        }
    }

}

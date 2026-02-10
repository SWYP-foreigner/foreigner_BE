package core.global.apple.service;

import core.domain.user.entity.User;
import core.domain.user.service.UserService;
import core.global.apple.client.AppleClient;
import core.global.apple.dto.AppleLoginByCodeRequest;
import core.global.apple.dto.ApplePublicKeyResponse;
import core.global.apple.dto.AppleRefreshTokenResponse;
import core.global.enums.errorcode.UserErrorCode;
import core.global.security.JwtTokenProvider;
import core.global.dto.*;
import core.global.enums.Ouathplatform;
import core.global.enums.errorcode.AuthErrorCode;
import core.global.exception.BusinessException;
import core.global.redis.service.RedisService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
            ApplePublicKeyResponse publicKeyResponse = appleKeyService.getApplePublicKeys();

            Map<String, String> headers = jwtProvider.parseHeaders(identityToken);
            String kidFromHeader = headers.get("kid");
            String algFromHeader = headers.get("alg");

            PublicKey publicKey = applePublicKeyGenerator.generate(headers, publicKeyResponse);

            Claims claims = jwtProvider.getTokenClaims(identityToken, publicKey);

            String expectedIssuer = "https://appleid.apple.com";
            String actualIssuer = claims.getIssuer();
            if (!expectedIssuer.equals(actualIssuer)) {
                throw new BusinessException(AuthErrorCode.INVALID_JWT_ISSUER);
            }


            String expectedAudience = appleProps.appBundleId();
            String actualAudience = claims.getAudience();
            if (!expectedAudience.equals(actualAudience)) {
                throw new BusinessException(AuthErrorCode.INVALID_JWT_AUDIENCE);
            }

            String nonceFromToken = claims.get("nonce", String.class);
            if (nonce == null || !nonce.equals(nonceFromToken)) {
                throw new BusinessException(AuthErrorCode.INVALID_JWT_NONCE);
            }

            return claims;
        } catch (BusinessException e) {
            log.error("Apple token verification failed with BusinessException: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Apple identityToken 검증 중 예상치 못한 심각한 오류 발생", e);
            throw new BusinessException(AuthErrorCode.INVALID_JWT_APPLE);
        }
    }
    @Transactional
    public LoginResponseDto login(AppleLoginByCodeRequest req) {
        Claims claims = verifyAndGetClaims(req.identityToken(), req.nonce());
        String socialId = claims.getSubject();
        String email = claims.get("email", String.class);
        String provider = Ouathplatform.APPLE.toString();
        User user = findOrCreateUser(socialId, email, provider, req);

        updateUserNameIfNeeded(user, req.fullName());
        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getUserRole().toString(), user.getEmail());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId());

        Date expirationDate = jwtTokenProvider.getExpiration(refreshToken);
        long expirationMillis = expirationDate.getTime() - System.currentTimeMillis();
        redisService.saveRefreshToken(user.getId(), refreshToken, expirationMillis);

        return new LoginResponseDto(user.getId(), accessToken, refreshToken, user.isNewUser());
    }

    /**
     * 유저를 찾거나, 없으면 새로 생성합니다.
     * ★ 중요: 이메일 중복 검사를 수행하여 DB 에러를 방지합니다.
     */
    private User findOrCreateUser(String socialId, String email, String provider, AppleLoginByCodeRequest req) {
        User user = userService.getUserBySocialIdAndProvider(socialId, provider);
        if (user != null) {
            return user;
        }

        if (email != null && userService.existsByEmail(email)) {
            throw new BusinessException(UserErrorCode.DUPLICATE_EMAIL_PROVIDER_MISMATCH);
        }

        String appleRefreshToken = requestAppleToken(req.authorizationCode());

        return userService.createAppleOauth(
                socialId,
                email,
                provider,
                appleRefreshToken,
                req.fullName()
        );
    }

    /**
     * Apple 로그인 요청에 이름 정보가 있고, 유저에게 업데이트가 필요한 경우 처리
     */
    private void updateUserNameIfNeeded(User user, AppleLoginByCodeRequest.FullNameDto fullName) {
        if (fullName == null) return;

        boolean needsUpdate = false;

        if (hasText(fullName.givenName()) && !fullName.givenName().equals(user.getFirstName())) {
            user.updateFirstName(fullName.givenName());
            needsUpdate = true;
        }

        if (hasText(fullName.familyName()) && !fullName.familyName().equals(user.getLastName())) {
            user.updateLastName(fullName.familyName());
            needsUpdate = true;
        }

        if (needsUpdate) {
            userService.updateUser(user, fullName);
        }
    }

    private boolean hasText(String str) {
        return str != null && !str.isBlank();
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

        try {
            AppleRefreshTokenResponse response = appleClient.getToken(formData);
            return response.refreshToken();
        } catch (Exception e) {
            log.error("Failed to get token from Apple server.", e);
            throw new BusinessException(AuthErrorCode.INVALID_APPLE_REQUEST);
        }
    }

}

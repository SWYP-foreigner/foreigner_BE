package core.global.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.domain.user.service.UserService;
import core.global.config.JwtTokenProvider;
import core.global.dto.*;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 애플(iOS 네이티브) 로그인/토큰 교환/연동 해제(Service 계층).
 * - 권장 플로우: App → 서버에 authorizationCode(+ rawNonce) 전달
 * - 서버: client_secret 생성 → /auth/token 교환 → id_token 검증(iss/aud/exp/nonce)
 * - 장기 로그인: refresh_token 서버 보관, 필요 시 /auth/token(Refresh) 호출
 * - 연동 해제: /auth/revoke 로 refresh_token 무효화
 */
@Service
@RequiredArgsConstructor
public class AppleAuthService {

    private final AppleClient appleClient;                       // Apple OAuth 서버 호출(Feign)
    private final AppleOAuthProperties props;                    // 팀/키/클라이언트/키PEM 등 설정
    private final AppleClientSecretProvider clientSecretProvider;// client_secret(JWT) 생성기(p8/ES256)
    private final AppleTokenParser tokenParser;                  // id_token 헤더/클레임 파서(서명 검증 포함)
    private final ApplePublicKeyGenerator keyGenerator;          // Apple JWK → RSAPublicKey 변환
    private final JwtTokenProvider jwtProvider;
    private final RedisService redisService;
    private final UserService userService;
    private final UserRepository userRepository;
    private final AppleKeyGenerator appleKeyGenerator;

    @Value("${oauth.apple.issuer}")
    private String issuer;
    @Value("${oauth.apple.client-id}")
    private String clientId;


    public void verifyIdentityToken(String identityToken) throws
            JsonProcessingException,
            NoSuchAlgorithmException,
            InvalidKeySpecException {
        // jwt 헤더를 파싱한다.
        Map<String, String> headers = jwtProvider.parseHeaders(identityToken);
        // 공개키를 생성한다
        PublicKey publicKey = keyGenerator.generatePublicKey(headers, getApplePublicKeys());
        // 토큰의 서명을 검사하고 Claim 을 반환받는다.
        Claims tokenClaims = jwtProvider.getTokenClaims(identityToken, publicKey);
        // iss 필드 검사
        if (!issuer.equals(tokenClaims.getIssuer())) {
            throw new BusinessException(ErrorCode.INVALID_JWT);
        }
        // aud 필드 검사
        if (!clientId.equals(tokenClaims.getAudience())) {
            throw new BusinessException(ErrorCode.INVALID_JWT);
        }
    }

    private ApplePublicKeyResponse getApplePublicKeys() {
        return appleClient.getApplePublicKeys();
    }




    public AppleTokenResponse getTokenFromApple(String authorizationCode,
                                                String codeVerifier,
                                                String redirectUri) {

        String clientSecret = appleKeyGenerator.getClientSecret();

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", authorizationCode);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("code_verifier", codeVerifier);
        form.add("redirect_uri", redirectUri);

        AppleTokenResponse token = appleClient.findAppleToken(form);

        if (token.getError() != null) {
            throw new BusinessException(ErrorCode.TOKEN_NOT_FOUND);
        }
        return token;
    }

    public AppleLoginResult makeToken(String identityToken, String appleRefreshToken) {
        // 1. id_token에서 유저 정보를 추출합니다.
        // verifyIdentityToken을 통해 이미 검증된 토큰이므로, 여기서 바로 클레임을 추출합니다.
        Claims claims = jwtProvider.getTokenClaims(identityToken, getPublicKeyFromToken(identityToken));

        // Apple 고유 ID를 가져옵니다.
        String appleSocialId = claims.getSubject();

        // 2. 추출한 Apple ID를 기반으로 회원가입 또는 로그인을 처리합니다.
        // UserService를 사용하여 DB에 사용자가 있는지 확인하고, 없으면 새로 생성합니다. 여기서 없으면 등록
        User user = userService.findOrCreateUser(appleSocialId);

        userRepository.save(user);
        // 3. 우리 서비스의 JWT(Access/Refresh Token)를 생성합니다.
        // JwtTokenProvider를 사용하여 생성하며, 이 토큰은 우리 서비스에 대한 인증에 사용됩니다.
        String accessToken = jwtProvider.createAccessToken(user.getId(),user.getEmail());
        String refreshToken = jwtProvider.createRefreshToken(user.getId());
        Date expirationDate = jwtProvider.getExpiration(refreshToken);
        long expirationMillis = expirationDate.getTime() - System.currentTimeMillis();
        redisService.saveRefreshToken(user.getId(), refreshToken, expirationMillis);

        // 5. 최종 결과 객체를 반환합니다. (accessToken 과 refreshToken 발급)
        return new AppleLoginResult(accessToken, refreshToken,user.getId(),user.getEmail(),user.isNewUser());
    }

    /**
     * ID_Token 으로 public key 를 만듬
     */
    private PublicKey getPublicKeyFromToken(String identityToken) {
        try {
            // 1. JWT 헤더를 파싱하여 kid(Key ID)와 alg(Algorithm)를 추출합니다.
            Map<String, String> headers = jwtProvider.parseHeaders(identityToken);
            String kid = headers.get("kid");
            String alg = headers.get("alg");

            if (kid == null || alg == null) {
                throw new BusinessException(ErrorCode.INVALID_JWT);
            }

            // 2. FeignClient를 사용해 애플의 공개키 목록을 가져옵니다.
            ApplePublicKeyResponse applePublicKeys = appleClient.getApplePublicKeys();

            // 3. kid와 alg가 일치하는 공개키를 찾습니다.
            ApplePublicKey matchedKey = applePublicKeys.getMatchedKey(kid, alg);


            // 4. 찾은 공개키 정보를 바탕으로 PublicKey 객체를 생성합니다.
            return keyGenerator.generate(matchedKey);

        } catch (Exception e) {
            // 예외 처리 로직 (e.g., 로깅)
            throw new BusinessException(ErrorCode.INVALID_JWT);
        }
    }

    /**
     * 이거는 탈퇴다.
     */
    @Transactional
    public void deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        String refreshToken = redisService.getRefreshToken(user.getId());

        /*
        1. appleToken 삭제
         */
        if (refreshToken != null) {
            revokeAppleToken(refreshToken);
        }
        /*
         2. Redis refresh_token 삭제
         */
        redisService.deleteRefreshToken(userId);

        /*
         3. DB에서 유저 삭제
         */
        userRepository.deleteById(userId);
    }


    private void revokeAppleToken(String appleRefreshToken) {
        String clientSecret = appleKeyGenerator.getClientSecret();
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId); // Use your configured client ID
        body.add("client_secret", clientSecret);
        body.add("token", appleRefreshToken);
        body.add("token_type_hint", "refresh_token");

        appleClient.revoke(body);
    }


}

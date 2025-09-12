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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
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

    @Value("${oauth.apple.issuer}")
    private String issuer;



    public Claims verifyAndGetClaims(String identityToken, String nonce) {
        try {
            ApplePublicKeyResponse publicKeyResponse = appleKeyService.getApplePublicKeys();
            List<ApplePublicKey> publicKeys = publicKeyResponse.keys();

            Map<String, String> headers = jwtProvider.parseHeaders(identityToken);
            PublicKey publicKey = applePublicKeyGenerator.generate(headers, publicKeyResponse);

            Claims claims = jwtProvider.getTokenClaims(identityToken, publicKey);

            if (!issuer.equals(claims.getIssuer())) {
                throw new BusinessException(ErrorCode.INVALID_JWT_ISSUER);
            }
            if (!appleProps.clientId().equals(claims.getAudience()))  {
                throw new BusinessException(ErrorCode.INVALID_JWT_AUDIENCE);
            }
            String nonceFromToken = claims.get("nonce", String.class);
            if (nonce == null || !nonce.equals(nonceFromToken)) {
                throw new BusinessException(ErrorCode.INVALID_JWT_NONCE);
            }

            return claims;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_JWT_APPLE);
        }
    }

    public LoginResponseDto login(AppleLoginByCodeRequest req) {
        log.info("1. 클라이언트로부터 받은 Identity Token을 검증하는 중...");
        Claims claims = verifyAndGetClaims(req.identityToken(), req.nonce());
        String appleSocialId = claims.getSubject();
        String provider = Ouathplatform.APPLE.toString();

        log.info("2. 데이터베이스에 기존 사용자가 있는지 확인하는 중...");
        User user = userService.getUserBySocialIdAndProvider(appleSocialId, provider);
        boolean isNewUser;

        if (user == null) {
            log.info("새로운 사용자입니다. 소셜 ID로 계정 생성");
            user = userService.createOauth(appleSocialId, req.email(),provider);
            isNewUser = true;
            log.info("새로운 사용자 계정 생성 완료. 사용자 ID: {}", user.getId());
        } else {
            isNewUser = false;
            log.info("기존 사용자 발견. 사용자 ID: {}", user.getId());
        }

        log.info("3. 인증된 사용자를 위한 새로운 JWT 토큰을 생성하는 중...");
        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId());

        Date expirationDate = jwtTokenProvider.getExpiration(refreshToken);
        long expirationMillis = expirationDate.getTime() - System.currentTimeMillis();
        redisService.saveRefreshToken(user.getId(), refreshToken, expirationMillis);

        return new LoginResponseDto(user.getId(), accessToken, refreshToken, isNewUser);
    }


}

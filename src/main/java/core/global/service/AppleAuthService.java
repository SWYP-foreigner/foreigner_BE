package core.global.service;

import core.global.dto.AppleLoginResult;
import core.global.dto.AppleTokenResponse;
import core.global.dto.AppleUserInfo;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Map;
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

    /**
     * Authorization Code → (서버) /auth/token 교환 → id_token 검증(+nonce) 후 로그인 결과 반환.
     *
     * @param authorizationCode  앱이 애플 네이티브 로그인으로 받은 코드(필수)
     * @param codeVerifierOrNull PKCE 사용 시 code_verifier(선택, 네이티브에선 보통 미사용)
     * @param redirectUriOrNull  redirect_uri (네이티브는 보통 비움, 필요 시만)
     * @param rawNonce           앱이 생성한 원본 nonce (performRequest에 sha256(nonce) 전달했을 때 필수)
     * @return                   사용자 식별/이메일 등 + 애플 토큰세트(액세스/리프레시/id_token)
     * @throws IllegalArgumentException iss/aud/nonce 등 검증 실패
     * @throws IllegalStateException    /auth/token 교환 실패(에러 코드 반환 등)
     */
    public AppleLoginResult loginWithAuthorizationCodeOnly(
            String authorizationCode,
            String codeVerifierOrNull,
            String redirectUriOrNull,
            String rawNonce
    ) {
        String clientSecret = clientSecretProvider.createClientSecret();

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", authorizationCode);
        form.add("client_id", props.clientId());     // iOS 네이티브: 번들ID
        form.add("client_secret", clientSecret);
        if (redirectUriOrNull != null && !redirectUriOrNull.isBlank()) {
            form.add("redirect_uri", redirectUriOrNull);
        }
        if (codeVerifierOrNull != null && !codeVerifierOrNull.isBlank()) {
            form.add("code_verifier", codeVerifierOrNull);
        }

        AppleTokenResponse token = appleClient.findAppleToken(form);
        if (token.getError() != null) {
            throw new IllegalStateException("Apple token error: " + token.getError());
        }

        Map<String, String> header = tokenParser.parseHeader(token.getIdToken());
        PublicKey publicKey = keyGenerator.generate(header, appleClient.getApplePublicKeys());
        Claims claims = tokenParser.extractClaims(token.getIdToken(), publicKey);

        if (!"https://appleid.apple.com".equals(claims.getIssuer())) {
            throw new IllegalArgumentException("invalid iss");
        }
        if (!claims.getAudience().contains(props.clientId())) {
            throw new IllegalArgumentException("invalid aud");
        }

        if (rawNonce != null && !rawNonce.isBlank()) {
            String nonceInToken = claims.get("nonce", String.class);
            String rawNonceSha256 = sha256Hex(rawNonce);
            if (nonceInToken == null || !nonceInToken.equalsIgnoreCase(rawNonceSha256)) {
                throw new IllegalArgumentException("invalid nonce");
            }
        }

        AppleUserInfo user = new AppleUserInfo(
                claims.getSubject(),
                claims.get("email", String.class),
                Optional.ofNullable(claims.get("email_verified", Boolean.class)).orElse(null)
        );

        return new AppleLoginResult(user, token);
    }

    /**
     * 주어진 문자열을 SHA-256으로 해싱해 소문자 hex로 반환.
     * - nonce 원문 → sha256 후 id_token의 nonce와 비교할 때 사용.
     */
    private String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * refresh_token으로 토큰 재발급.
     * - 서버가 보관 중인 refresh_token으로 /auth/token 호출(grant_type=refresh_token).
     * - Apple이 error를 반환하면 예외 처리(예: invalid_grant = 만료/폐기).
     *
     * @param refreshToken 애플 refresh_token
     * @return             새로운 토큰 세트(액세스/리프레시/id_token)
     */
    public AppleTokenResponse refresh(String refreshToken) {
        // 1) client_secret 생성
        String clientSecret = clientSecretProvider.createClientSecret();

        // 2) /auth/token (grant_type=refresh_token)
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", props.clientId());
        form.add("client_secret", clientSecret);

        AppleTokenResponse token = appleClient.findAppleToken(form);

        // 3) 에러 핸들링
        if (token.getError() != null) {
            throw new IllegalStateException("Apple refresh error: " + token.getError());
        }
        return token;
    }

    /**
     * 애플 연동 해제(revoke).
     * - 보통 refresh_token을 무효화한다.
     * - 성공 시 204 No Content, 실패 시 Feign 예외가 터질 수 있음.
     *
     * @param refreshToken 무효화할 애플 refresh_token
     */
    public void revoke(String refreshToken) {
        // 1) client_secret 생성
        String clientSecret = clientSecretProvider.createClientSecret();

        // 2) /auth/revoke 호출(token_type_hint=refresh_token)
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", props.clientId());
        form.add("client_secret", clientSecret);
        form.add("token", refreshToken);
        form.add("token_type_hint", "refresh_token");

        appleClient.revoke(form);
    }
}

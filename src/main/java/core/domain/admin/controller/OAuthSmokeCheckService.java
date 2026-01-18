package core.domain.admin.controller;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;

@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthSmokeCheckService {
    private final RestTemplate restTemplate;

    @Value("${oauth.google.web.client-id}")
    private String googleClientId;
    @Value("${oauth.google.web.client-secret}")
    private String googleClientSecret;
    @Value("${oauth.apple.client-id}")
    private String appleClientId;
    @Value("${oauth.apple.team-id}")
    private String appleTeamId;
    @Value("${oauth.apple.key-id}")
    private String appleKeyId;
    @Value("${oauth.apple.private-key-pem}")
    private String applePrivateKeyPem;

    public String googleChecker() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", googleClientId);
        body.add("client_secret", googleClientSecret);
        body.add("grant_type", "authorization_code");
        body.add("code", "smoke_test");

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        try {
            restTemplate.postForEntity("https://oauth2.googleapis.com/token", request, String.class);
            return "GOOGLE_SECRET_OK";
        } catch (HttpClientErrorException e) {
            // 400 Bad Request가 오면 성공으로 간주하는 로직
            if (e.getStatusCode().value() == 400) return "GOOGLE_SECRET_VALID";
            throw new RuntimeException("GOOGLE_SECRET_INVALID: " + e.getResponseBodyAsString());
        }
    }

    public String appleChecker() {
        try {
            String clientSecret = createAppleClientSecret();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("client_id", appleClientId);
            body.add("client_secret", clientSecret);
            body.add("grant_type", "authorization_code");
            body.add("code", "smoke_test");

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

            restTemplate.postForEntity("https://appleid.apple.com/auth/token", request, String.class);
            return "APPLE_SECRET_OK";

        } catch (HttpClientErrorException e) {
            String body = e.getResponseBodyAsString();
            if (body.contains("invalid_grant")) return "APPLE_SECRET_VALID";
            throw new RuntimeException("APPLE_SECRET_INVALID: " + body);
        } catch (Exception e) {
            throw new RuntimeException("APPLE_INTERNAL_ERROR: " + e.getMessage());
        }
    }

    private String createAppleClientSecret() throws Exception {
        Date now = new Date();
        return Jwts.builder()
                .setHeaderParam("kid", appleKeyId)
                .setIssuer(appleTeamId)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + 1000 * 60 * 5))
                .setAudience("https://appleid.apple.com")
                .setSubject(appleClientId)
                .signWith(getApplePrivateKey(), SignatureAlgorithm.ES256)
                .compact();
    }

    private PrivateKey getApplePrivateKey() throws Exception {
        try {
            // 1. 전체가 Base64로 인코딩된 PEM 문자열을 먼저 디코딩
            byte[] decodedBytes = Base64.getDecoder().decode(applePrivateKeyPem.trim());
            String decodedPem = new String(decodedBytes);

            // 2. PEM 헤더/푸터 및 모든 공백 제거
            String pemBody = decodedPem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");

            // 3. 바디를 다시 디코딩하여 PKCS#8 생성
            byte[] pkcs8EncodedKey = Base64.getDecoder().decode(pemBody);
            KeyFactory kf = KeyFactory.getInstance("EC");
            return kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8EncodedKey));
        } catch (IllegalArgumentException e) {
            log.error("Apple Private Key 디코딩 실패. 환경 변수 형식을 확인하세요.");
            throw new RuntimeException("APPLE_KEY_DECODE_ERROR");
        }
    }
}
package core.global.admin.controller;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;

@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthSmokeCheckService {
    private final WebClient.Builder webClientBuilder;

    @Value("${oauth.google.web.client-id}") private String googleClientId;
    @Value("${oauth.google.web.client-secret}") private String googleClientSecret;
    @Value("${oauth.apple.client-id}") private String appleClientId;
    @Value("${oauth.apple.team-id}") private String appleTeamId;
    @Value("${oauth.apple.key-id}") private String appleKeyId;
    @Value("${oauth.apple.private-key-pem}") private String applePrivateKeyPem;

    public String googleChecker() {
        WebClient webClient = webClientBuilder.build();
        try {
            webClient.post()
                    .uri("https://oauth2.googleapis.com/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue("client_id=" + googleClientId + "&client_secret=" + googleClientSecret +
                               "&grant_type=authorization_code&code=smoke_test")
                    .retrieve().toEntity(String.class).block();
            return "GOOGLE_SECRET_OK";
        } catch (WebClientResponseException e) {
            // 400이면 키는 맞는데 코드가 틀린 것이므로 '유효'
            if (e.getStatusCode().value() == 400) return "GOOGLE_SECRET_VALID";
            throw new RuntimeException("GOOGLE_SECRET_INVALID: " + e.getResponseBodyAsString());
        }
    }

    public String appleChecker() {
        WebClient webClient = webClientBuilder.build();
        try {
            String clientSecret = createAppleClientSecret();
            webClient.post()
                    .uri("https://appleid.apple.com/auth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue("client_id=" + appleClientId + "&client_secret=" + clientSecret +
                               "&grant_type=authorization_code&code=smoke_test")
                    .retrieve().toEntity(String.class).block();
            return "APPLE_SECRET_OK";
        } catch (WebClientResponseException e) {
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
        String pem = applePrivateKeyPem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
    }
}
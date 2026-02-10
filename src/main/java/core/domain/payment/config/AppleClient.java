package core.domain.payment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.SignedJWT;
import core.domain.payment.dto.AppleTransactionInfo;
import core.global.enums.payment.PurchaseStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.*;

@Slf4j // 로그 사용
@Component
public class AppleClient {

    private static final String APPLE_KEYS_URL = "https://apple-public-keys.s3-us-west-2.amazonaws.com/keys.json";
    private static final String BASE_URL_PROD = "https://api.storekit.itunes.apple.com";
    private static final String BASE_URL_SB = "https://api.storekit-sandbox.itunes.apple.com";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String issuerId;
    private final String keyId;
    private final String bundleId;
    private final String environment;

    // 파싱된 키를 미리 저장해두는 변수 (성능 + 유효성 체크용)
    private ECPrivateKey applePrivateKey;
    private boolean isInitialized = false;

    // ★ 수정 1: 생성자 주입 방식으로 변경하여 안전하게 초기화
    public AppleClient(
            @Value("${iap.ios.issuerId:null}") String issuerId,
            @Value("${iap.ios.keyId:null}") String keyId,
            @Value("${iap.ios.privateKeyBase64:null}") String privateKeyBase64,
            @Value("${iap.ios.bundleId:null}") String bundleId,
            @Value("${iap.ios.environment:Production}") String environment
    ) {
        this.issuerId = issuerId;
        this.keyId = keyId;
        this.bundleId = bundleId;
        this.environment = environment;

        try {
            // 설정값 누락 체크
            if (isInvalid(issuerId) || isInvalid(keyId) || isInvalid(privateKeyBase64) || isInvalid(bundleId)) {
                log.warn("Apple IAP 설정이 누락되었습니다. AppleClient 기능이 비활성화됩니다.");
            } else {
                // 키 파싱 시도 (여기서 에러나면 catch로 감)
                this.applePrivateKey = loadECPrivateKeyFromBase64P8(privateKeyBase64);
                this.isInitialized = true;
                log.info("AppleClient 초기화 성공");
            }
        } catch (Exception e) {
            // ★ 수정 2: 키 형식이 잘못되어도 앱 셧다운 방지
            log.error("AppleClient 초기화 실패 (키 형식 오류 등): {}", e.getMessage());
            this.isInitialized = false;
        }
    }

    // 헬퍼 메소드
    private boolean isInvalid(String value) {
        return value == null || "null".equals(value) || value.isBlank();
    }

    public AppleTransactionInfo getTransaction(String transactionId) {
        // ★ 수정 3: 초기화 실패 상태면 null 반환 (에러 throw 안 함)
        if (!isInitialized || applePrivateKey == null) {
            log.error("AppleClient가 초기화되지 않아 트랜잭션을 조회할 수 없습니다.");
            return null;
        }

        try {
            String devToken = createDeveloperToken();
            String baseUrl = "Production".equalsIgnoreCase(environment) ? BASE_URL_PROD : BASE_URL_SB;
            String url = baseUrl + "/inApps/v1/transactions/" + transactionId;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + devToken)
                    .GET()
                    .build();

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() / 100 != 2) {
                log.error("Apple API 응답 오류: Status Code {}", resp.statusCode());
                return null;
            }

            Map<String, Object> json = objectMapper.readValue(resp.body(), Map.class);
            String signedTransactionInfo = Objects.toString(json.get("signedTransactionInfo"), null);

            if (signedTransactionInfo == null) {
                log.error("Apple 응답에 signedTransactionInfo가 없습니다.");
                return null;
            }

            String payloadJson = verifyAppleJwsAndGetPayload(signedTransactionInfo);
            Map<String, Object> claims = objectMapper.readValue(payloadJson, Map.class);

            String txId = Objects.toString(claims.get("transactionId"), null);
            String originalTxId = Objects.toString(claims.get("originalTransactionId"), null);
            String productId = Objects.toString(claims.get("productId"), null);
            Instant purchaseDate = toInstant(claims.get("purchaseDate"));
            Instant expiresDate = toInstant(claims.get("expiresDate"));
            boolean revoked = claims.get("revocationDate") != null;
            PurchaseStatus status = mapAppleStatus(expiresDate, revoked);

            return new AppleTransactionInfo(
                    txId, originalTxId, productId, purchaseDate, expiresDate, status, payloadJson
            );

        } catch (Exception e) {
            // ★ 수정 4: 런타임 에러도 로그만 남기고 null 반환
            log.error("Apple 트랜잭션 조회 중 오류: {}", e.getMessage());
            return null;
        }
    }

    private String createDeveloperToken() throws Exception {
        Instant now = Instant.now();
        JWSSigner signer = new ECDSASigner(applePrivateKey); // 미리 파싱된 키 사용

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .keyID(keyId)
                .type(JOSEObjectType.JWT)
                .build();

        // (기존 로직 동일)
        SignedJWT jwt = new SignedJWT(header, new com.nimbusds.jwt.JWTClaimsSet.Builder()
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(60 * 20)))
                .audience("https://api.storekit.itunes.apple.com")
                .issuer(issuerId)
                .claim("bid", bundleId)
                .build());

        jwt.sign(signer);
        return jwt.serialize();
    }

    private ECPrivateKey loadECPrivateKeyFromBase64P8(String base64OfP8Pem) throws Exception {
        byte[] p8Bytes = Base64.getDecoder().decode(base64OfP8Pem);
        String p8 = new String(p8Bytes);
        String normalized = p8.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] pkcs8 = Base64.getDecoder().decode(normalized);

        KeyFactory kf = KeyFactory.getInstance("EC");
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(pkcs8);
        return (ECPrivateKey) kf.generatePrivate(spec);
    }

    private String verifyAppleJwsAndGetPayload(String jws) throws Exception {
        // (기존 로직 동일)
        JWSObject jwsObject = JWSObject.parse(jws);
        JWKSource<SecurityContext> jwkSource = new RemoteJWKSet<>(new URL(APPLE_KEYS_URL));
        JWKSelector selector = new JWKSelector(new JWKMatcher.Builder().keyID(jwsObject.getHeader().getKeyID()).build());
        List<JWK> jwks = jwkSource.get(selector, null);
        if (jwks.isEmpty()) throw new RuntimeException("Apple public key not found");

        JWK jwk = jwks.get(0);
        JWSVerifier verifier;
        if (jwk instanceof ECKey ecKey) {
            verifier = new ECDSAVerifier(ecKey);
        } else {
            throw new RuntimeException("Unexpected JWK type");
        }

        if (!jwsObject.verify(verifier)) {
            throw new RuntimeException("Apple JWS signature verification failed");
        }
        return jwsObject.getPayload().toString();
    }

    private Instant toInstant(Object epochMillis) {
        if (epochMillis == null) return null;
        long ms = Long.parseLong(String.valueOf(epochMillis));
        return Instant.ofEpochMilli(ms);
    }

    private PurchaseStatus mapAppleStatus(Instant expires, boolean revoked) {
        if (revoked) return PurchaseStatus.REFUNDED;
        Instant now = Instant.now();
        if (expires == null) return PurchaseStatus.ACTIVE;
        if (expires.isAfter(now)) return PurchaseStatus.ACTIVE;
        return PurchaseStatus.EXPIRED;
    }
}
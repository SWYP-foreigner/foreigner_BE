package core.domain.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.SignedJWT;
import core.domain.payment.dto.AppleTransactionInfo;
import core.global.enums.errorcode.PaymentErrorCode;
import core.global.enums.payment.PurchaseStatus;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
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

@Component
@RequiredArgsConstructor
public class AppleClient {

    private static final String APPLE_KEYS_URL = "https://apple-public-keys.s3-us-west-2.amazonaws.com/keys.json";
    private static final String BASE_URL_PROD = "https://api.storekit.itunes.apple.com";
    private static final String BASE_URL_SB = "https://api.storekit-sandbox.itunes.apple.com";
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Value("${iap.ios.issuerId}")
    private String issuerId;
    @Value("${iap.ios.keyId}")
    private String keyId;
    @Value("${iap.ios.privateKeyBase64}")
    private String privateKeyBase64; // p8 원문을 Base64로 주입
    @Value("${iap.ios.bundleId}")
    private String bundleId;
    @Value("${iap.ios.environment}")
    private String environment; // Production | Sandbox

    public AppleTransactionInfo getTransaction(String transactionId) {
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
                throw new BusinessException(PaymentErrorCode.APPLE_TRANSACTION_FAILED);
            }

            Map<String, Object> json = objectMapper.readValue(resp.body(), Map.class);
            String signedTransactionInfo = Objects.toString(json.get("signedTransactionInfo"), null);
            if (signedTransactionInfo == null) {
                throw new BusinessException(PaymentErrorCode.APPLE_SIGNED_TRANSACTIONINFO_MISSING);
            }

            // 서명 검증 + 페이로드 파싱
            String payloadJson = verifyAppleJwsAndGetPayload(signedTransactionInfo);
            Map<String, Object> claims = objectMapper.readValue(payloadJson, Map.class);

            // 트랜잭션 정보 매핑
            String txId = Objects.toString(claims.get("transactionId"), null);
            String originalTxId = Objects.toString(claims.get("originalTransactionId"), null);
            String productId = Objects.toString(claims.get("productId"), null);

            // 날짜(밀리초 epoch)
            Instant purchaseDate = toInstant(claims.get("purchaseDate"));
            Instant expiresDate = toInstant(claims.get("expiresDate"));
            // 환불/철회 여부
            boolean revoked = claims.get("revocationDate") != null;

            // 상태 매핑
            PurchaseStatus status = mapAppleStatus(expiresDate, revoked);

            return new AppleTransactionInfo(
                    txId, originalTxId, productId, purchaseDate, expiresDate, status, payloadJson
            );
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(PaymentErrorCode.APPLE_TRANSACTION_FAILED, e);
        }
    }

    private String createDeveloperToken() throws Exception {
        // ES256 JWT (kid=keyId, iss=issuerId, aud=https://api.storekit.itunes.apple.com, bid=bundleId)
        Instant now = Instant.now();
        JWSSigner signer = new ECDSASigner(loadECPrivateKeyFromBase64P8(privateKeyBase64));

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .keyID(keyId)
                .type(JOSEObjectType.JWT)
                .build();

        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", issuerId);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plusSeconds(60 * 20).getEpochSecond()); // 20분
        claims.put("aud", "https://api.storekit.itunes.apple.com");
        claims.put("bid", bundleId);

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
        // base64(p8-plain-text) → p8 원문
        byte[] p8Bytes = Base64.getDecoder().decode(base64OfP8Pem);
        String p8 = new String(p8Bytes);
        // -----BEGIN PRIVATE KEY----- PEM → PKCS8 key bytes
        String normalized = p8.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] pkcs8 = Base64.getDecoder().decode(normalized);

        KeyFactory kf = KeyFactory.getInstance("EC");
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(pkcs8);
        return (ECPrivateKey) kf.generatePrivate(spec);
    }

    private String verifyAppleJwsAndGetPayload(String jws) throws Exception {
        // kid로 Apple JWKS에서 공개키 선택 → 검증
        JWSObject jwsObject = JWSObject.parse(jws);
        JWKSource<SecurityContext> jwkSource = new RemoteJWKSet<>(new URL(APPLE_KEYS_URL));
        JWKSelector selector = new JWKSelector(new JWKMatcher.Builder().keyID(jwsObject.getHeader().getKeyID()).build());
        List<JWK> jwks = jwkSource.get(selector, null);
        if (jwks.isEmpty())
            throw new RuntimeException("Apple public key not found for kid=" + jwsObject.getHeader().getKeyID());

        JWK jwk = jwks.get(0);
        JWSVerifier verifier;
        if (jwk instanceof ECKey ecKey) {
            verifier = new ECDSAVerifier(ecKey);
        } else {
            throw new RuntimeException("Unexpected JWK type: " + jwk.getClass());
        }

        if (!jwsObject.verify(verifier)) {
            throw new RuntimeException("Apple JWS signature verification failed");
        }
        // Payload는 JSON 문자열
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
        if (expires == null) return PurchaseStatus.ACTIVE; // 일회성 등
        if (expires.isAfter(now)) return PurchaseStatus.ACTIVE;
        return PurchaseStatus.EXPIRED;
    }
}
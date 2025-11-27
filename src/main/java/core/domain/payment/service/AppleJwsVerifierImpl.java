package core.domain.payment.service;

import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import core.global.enums.errorcode.PaymentErrorCode;
import core.global.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.List;

@Component
public class AppleJwsVerifierImpl implements AppleJwsVerifier {
    private static final String APPLE_KEYS_URL = "https://apple-public-keys.s3-us-west-2.amazonaws.com/keys.json";

    @Override
    public String verifyAndGetPayload(String jws) {
        try {
            // 1. JWS 파싱
            JWSObject jwsObject;
            try {
                jwsObject = JWSObject.parse(jws);
            } catch (Exception e) {
                throw new BusinessException(PaymentErrorCode.APPLE_JWS_PARSE_FAILED, e);
            }

            // 2. kid 기반으로 Apple 공개 키 조회
            JWKSource<SecurityContext> jwkSource;
            try {
                jwkSource = new RemoteJWKSet<>(new URL(APPLE_KEYS_URL));
            } catch (Exception e) {
                // URL 설정 자체가 문제라면 서버 문제로 보는 것도 가능
                throw new BusinessException(PaymentErrorCode.APPLE_WEBHOOK_FAILED, e);
            }

            JWKSelector selector = new JWKSelector(
                    new JWKMatcher.Builder()
                            .keyID(jwsObject.getHeader().getKeyID())
                            .build()
            );

            List<JWK> jwks;
            try {
                jwks = jwkSource.get(selector, null);
            } catch (Exception e) {
                // 네트워크 오류 등
                throw new BusinessException(PaymentErrorCode.APPLE_WEBHOOK_FAILED, e);
            }

            if (jwks == null || jwks.isEmpty()) {
                throw new BusinessException(PaymentErrorCode.APPLE_KEY_NOT_FOUND);
            }

            // 3. EC 공개키 검증
            JWK jwk = jwks.get(0);
            if (!(jwk instanceof ECKey ecKey)) {
                throw new BusinessException(PaymentErrorCode.APPLE_JWK_TYPE_MISMATCH);
            }

            JWSVerifier verifier = new ECDSAVerifier(ecKey);

            // 4. 서명 검증
            boolean verified;
            try {
                verified = jwsObject.verify(verifier);
            } catch (Exception e) {
                throw new BusinessException(PaymentErrorCode.APPLE_VERIFY_FAILED, e);
            }

            if (!verified) {
                throw new BusinessException(PaymentErrorCode.APPLE_VERIFY_FAILED);
            }

            // 5. 검증 성공 → payload 반환
            return jwsObject.getPayload().toString();

        } catch (BusinessException e) {
            // 이미 매핑된 도메인 예외는 그대로 전파
            throw e;
        } catch (Exception e) {
            // 혹시 빠져나온 예외는 Apple 웹훅 전체 실패로 처리
            throw new BusinessException(PaymentErrorCode.APPLE_WEBHOOK_FAILED, e);
        }
    }
}
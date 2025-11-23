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
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.List;

@Component
public class AppleJwsVerifierImpl implements AppleJwsVerifier {
    private static final String APPLE_KEYS_URL = "https://apple-public-keys.s3-us-west-2.amazonaws.com/keys.json";

    @Override
    public String verifyAndGetPayload(String jws) {
        try {
            JWSObject jwsObject = JWSObject.parse(jws);
            JWKSource<SecurityContext> jwkSource = new RemoteJWKSet<>(new URL(APPLE_KEYS_URL));
            JWKSelector selector = new JWKSelector(new JWKMatcher.Builder()
                    .keyID(jwsObject.getHeader().getKeyID()).build());
            List<JWK> jwks = jwkSource.get(selector, null);
            if (jwks.isEmpty()) throw new RuntimeException("Apple key not found for kid");

            JWK jwk = jwks.get(0);
            if (!(jwk instanceof ECKey ecKey)) {
                throw new RuntimeException("Unexpected JWK type: " + jwk.getClass());
            }
            JWSVerifier verifier = new ECDSAVerifier(ecKey);
            if (!jwsObject.verify(verifier)) throw new RuntimeException("Apple JWS signature invalid");
            return jwsObject.getPayload().toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
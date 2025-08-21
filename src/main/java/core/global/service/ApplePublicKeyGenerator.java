package core.global.service;


import core.global.dto.ApplePublicKeys;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Map;

@Component
public class ApplePublicKeyGenerator {

    /**
     * 주어진 id_token 헤더 정보(alg, kid)에 해당하는 JWK를 JWKS에서 찾아
     * RSA PublicKey 객체로 변환한다.
     */
    public PublicKey pickAndBuild(Map<String, String> header, ApplePublicKeys jwks) {
        String alg = header.get("alg");
        String kid = header.get("kid");

        ApplePublicKeys.Key match = jwks.getKeys().stream()
                .filter(k -> alg.equals(k.getAlg()) && kid.equals(k.getKid()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("매칭되는 Apple JWK 없음"));

        return toRsaPublicKey(match.getN(), match.getE());
    }
    /**
     * JWK의 모듈러스(N), 지수(E) 값을 이용해 RSA 공개키를 생성한다.*/
    private PublicKey toRsaPublicKey(String nB64u, String eB64u) {
        try {
            byte[] nBytes = Base64.getUrlDecoder().decode(nB64u); // URL-safe Base64 (JWT/JWK 규격)
            byte[] eBytes = Base64.getUrlDecoder().decode(eB64u);

            RSAPublicKeySpec spec = new RSAPublicKeySpec(
                    new BigInteger(1, nBytes),
                    new BigInteger(1, eBytes)
            );
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Exception ex) {
            throw new IllegalStateException("RSA 공개키 생성 실패", ex);
        }
    }

    /** id_token 헤더의 alg/kid로 JWK 선택 후 RSA PublicKey 생성 */
    public PublicKey generate(Map<String, String> header, ApplePublicKeys jwks) {
        String alg = header.get("alg");
        String kid = header.get("kid");

        ApplePublicKeys.Key match = jwks.getKeys().stream()
                .filter(k -> alg.equals(k.getAlg()) && kid.equals(k.getKid()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("매칭되는 Apple JWK 없음"));

        return toRsaPublicKey(match.getN(), match.getE());
    }
}
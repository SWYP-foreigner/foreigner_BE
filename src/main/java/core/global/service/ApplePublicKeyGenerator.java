package core.global.service;


import core.global.dto.ApplePublicKey;
import core.global.dto.ApplePublicKeyResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Map;


@Component
@RequiredArgsConstructor
public class ApplePublicKeyGenerator {

    /**
     *  kid,alg 으로 PubliKey를 가져옴
     */
    public PublicKey generatePublicKey(Map<String, String> tokenHeaders,
                                       ApplePublicKeyResponse applePublicKeys) throws NoSuchAlgorithmException, InvalidKeySpecException {
        ApplePublicKey publicKey = applePublicKeys.getMatchedKey(tokenHeaders.get("kid"),
                tokenHeaders.get("alg"));
        return getPublicKey(publicKey);
    }


    private PublicKey getPublicKey(ApplePublicKey publicKey)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        byte[] nBytes = Base64.getUrlDecoder().decode(publicKey.n());
        byte[] eBytes = Base64.getUrlDecoder().decode(publicKey.e());
        RSAPublicKeySpec publicKeySpec = new RSAPublicKeySpec(new BigInteger(1, nBytes),
                new BigInteger(1, eBytes));
        KeyFactory keyFactory = KeyFactory.getInstance(publicKey.kty());
        return keyFactory.generatePublic(publicKeySpec);
    }

    /**
     * ApplePublicKey 객체를 사용하여 PublicKey 객체 생성
     */
    public PublicKey generate(ApplePublicKey publicKey)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        byte[] nBytes = Base64.getUrlDecoder().decode(publicKey.n());
        byte[] eBytes = Base64.getUrlDecoder().decode(publicKey.e());
        RSAPublicKeySpec publicKeySpec = new RSAPublicKeySpec(new BigInteger(1, nBytes),
                new BigInteger(1, eBytes));
        KeyFactory keyFactory = KeyFactory.getInstance(publicKey.kty()); // "RSA"
        return keyFactory.generatePublic(publicKeySpec);
    }
}

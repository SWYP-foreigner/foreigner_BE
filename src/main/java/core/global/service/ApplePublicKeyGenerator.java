package core.global.service;

import core.global.dto.ApplePublicKey;
import core.global.dto.ApplePublicKeyResponse;
import core.global.exception.BusinessException;
import core.global.enums.ErrorCode;
import org.springframework.stereotype.Component;
import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.*;
import java.util.Base64;
import java.util.Map;

@Component
public class ApplePublicKeyGenerator {

    /**
     * 토큰 헤더와 공개키 목록을 받아, 일치하는 공개키(PublicKey) 객체를 생성하여 반환합니다.
     */
    public PublicKey generate(Map<String, String> tokenHeaders, ApplePublicKeyResponse publicKeyResponse) {
        ApplePublicKey matchedKey = publicKeyResponse.keys().stream()
                .filter(key ->
                        key.kid().equals(tokenHeaders.get("kid")) &&
                                "ES256".equals(key.alg())
                )
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NO_MATCHING_APPLE_KEY));

        return createPublicKey(matchedKey);
    }

    /**
     * ApplePublicKey DTO를 실제 PublicKey 객체로 변환하는 private 헬퍼 메소드입니다.
     * ⭐️ RSA가 아닌 EC(타원 곡선) 키 생성 로직으로 변경되었습니다.
     */
    private PublicKey createPublicKey(ApplePublicKey keyDto) {
        try {
            byte[] xBytes = Base64.getUrlDecoder().decode(keyDto.x());
            byte[] yBytes = Base64.getUrlDecoder().decode(keyDto.y());
            BigInteger x = new BigInteger(1, xBytes);
            BigInteger y = new BigInteger(1, yBytes);

            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec parameterSpec = parameters.getParameterSpec(ECParameterSpec.class);

            ECPoint point = new ECPoint(x, y);
            ECPublicKeySpec publicKeySpec = new ECPublicKeySpec(point, parameterSpec);

            KeyFactory keyFactory = KeyFactory.getInstance("EC");

            return keyFactory.generatePublic(publicKeySpec);

        }  catch (NoSuchAlgorithmException | InvalidKeySpecException | java.security.spec.InvalidParameterSpecException e) {
        throw new BusinessException(ErrorCode.PUBLIC_KEY_GENERATION_FAILED);
    }
    }
}
package core.global.service;

import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.Date;

@Slf4j
@Component
@RequiredArgsConstructor
public class AppleClientSecretGenerator {

    private final AppleOAuthProperties appleProps;

    /**
     * Apple 서버와 통신하기 위한 client_secret JWT를 생성합니다.
     * 유효 기간은 최대 6개월입니다.
     * @return 생성된 client_secret (String)
     */
    public String generateClientSecret() {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + (1000L * 3600 * 24 * 60));

        PrivateKey privateKey = createPrivateKey();

        return Jwts.builder()
                .setHeaderParam("kid", appleProps.keyId())
                .setHeaderParam("alg", "ES256")
                .setIssuer(appleProps.teamId())
                .setIssuedAt(now)
                .setExpiration(expiration)
                .setAudience("https://appleid.apple.com")
                .setSubject(appleProps.clientId())
                .signWith(privateKey, SignatureAlgorithm.ES256)
                .compact();
    }

    /**
     * application.yml에 저장된 Base64 인코딩된 p8 private key를 PrivateKey 객체로 변환합니다.
     * @return PrivateKey 객체
     */
    private PrivateKey createPrivateKey() {
        try {
            String encodedKey = appleProps.privateKeyPem();
            log.info("--- Decoding Apple Private Key ---");
            log.info("Original Base64 Encoded Key (first 30 chars): {}...", encodedKey.substring(0, 30));
            log.info("Encoded Key Length: {}", encodedKey.length());

            byte[] decodedKey = Base64.getDecoder().decode(encodedKey);
            String keyString = new String(decodedKey);

            log.info("Decoded PEM Key (first 30 chars): {}...", keyString.substring(0, 30));
            log.info("---------------------------------");


            try (StringReader keyReader = new StringReader(keyString);
                 PEMParser pemParser = new PEMParser(keyReader)) {

                JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
                PrivateKeyInfo privateKeyInfo = (PrivateKeyInfo) pemParser.readObject();
                return converter.getPrivateKey(privateKeyInfo);
            }

        } catch (IOException e) {
            log.error("Failed to parse Apple private key.", e);
            throw new BusinessException(ErrorCode.INVALID_PRIVATE_KEY_APPLE);
        }
    }
}
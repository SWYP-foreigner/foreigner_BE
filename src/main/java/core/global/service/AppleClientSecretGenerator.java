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

    private PrivateKey createPrivateKey() {
        log.info("--- Private Key Processing Start ---");
        try {
            String privateKeyPem = appleProps.privateKeyPem();
            // Log 1: Raw string from properties
            log.info("1. Raw privateKeyPem string: {}", privateKeyPem);

            byte[] decodedKey = Base64.getDecoder().decode(privateKeyPem);
            String keyString = new String(decodedKey);

            // Log 2: The decoded key string (should be in PEM format)
            log.info("2. Decoded keyString (PEM format):\n{}", keyString);

            try (StringReader keyReader = new StringReader(keyString);
                 PEMParser pemParser = new PEMParser(keyReader)) {

                JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
                Object parsedObject = pemParser.readObject();
                if (parsedObject == null) {
                    log.error("Failed to parse PEM object. pemParser returned null.");
                    throw new IOException("Failed to parse PEM object.");
                }
                log.info("3. PEMParser parsed object type: {}", parsedObject.getClass().getName());

                PrivateKeyInfo privateKeyInfo = (PrivateKeyInfo) parsedObject;
                return converter.getPrivateKey(privateKeyInfo);
            }

        } catch (IOException e) {
            log.error("Failed to parse Apple private key. Check key format and properties.", e);
            throw new BusinessException(ErrorCode.INVALID_PRIVATE_KEY_APPLE);
        } finally {
            log.info("--- Private Key Processing End ---");
        }
    }
}
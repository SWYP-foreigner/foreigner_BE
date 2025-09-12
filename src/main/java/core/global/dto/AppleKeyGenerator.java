package core.global.dto;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.RequiredArgsConstructor;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.security.PrivateKey;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

@Component
public class AppleKeyGenerator {

    @Value("${oauth.apple.key-id}")
    private String kid;

    @Value("${oauth.apple.team-id}")
    private String teamId;

    @Value("${oauth.apple.client-id}")
    private String appId;

    @Value("${oauth.apple.private-key-pem}")
    private String privateKey;

    /**
     * apple client secret 을 생성한다.
     */
    public String getClientSecret() {
        Date expirationDate = Date.from(LocalDateTime.now().plusDays(30).atZone(ZoneId.systemDefault()).toInstant());

        try {
            return Jwts.builder()
                    .setHeaderParam("kid", kid)
                    .setHeaderParam("alg", "ES256")
                    .setIssuer(teamId)
                    .setIssuedAt(new Date(System.currentTimeMillis()))
                    .setExpiration(expirationDate)
                    .setAudience("https://appleid.apple.com")
                    .setSubject(appId)
                    .signWith(SignatureAlgorithm.ES256, getPrivateKey())
                    .compact();
        } catch (IOException e) {
            // 예외 처리 로직 (e.g., 로깅)
            throw new RuntimeException("Failed to generate Apple client secret", e);
        }
    }

    /**
     * apple private 키를 반환한다.
     */
    private PrivateKey getPrivateKey() throws IOException {
        Reader pemReader = new StringReader(privateKey.replace("\\n", "\n"));
        PEMParser pemParser = new PEMParser(pemReader);
        JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
        PrivateKeyInfo object = (PrivateKeyInfo)pemParser.readObject();
        return converter.getPrivateKey(object);
    }
}
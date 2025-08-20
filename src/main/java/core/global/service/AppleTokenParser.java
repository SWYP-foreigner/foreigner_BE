package core.global.service;


import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Map;

@Component
public class AppleTokenParser {

    private final ObjectMapper om = new ObjectMapper();
    /** JWT 헤더를 Map으로 (alg, kid 등) */
    @SuppressWarnings("unchecked")
    public Map<String, String> parseHeader(String jwt) {
        try {
            String encHeader = jwt.split("\\.")[0];
            String json = new String(Base64.getUrlDecoder().decode(encHeader), StandardCharsets.UTF_8);
            return om.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("JWT 헤더 파싱 실패", e);
        }
    }

    /** JJWT 0.11.x: 공개키 서명 검증 후 Claims 추출 */
    public Claims extractClaims(String jwt, PublicKey publicKey) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(publicKey)   // 0.11.x 방식
                    .build()
                    .parseClaimsJws(jwt)
                    .getBody();
        } catch (Exception e) {
            throw new IllegalArgumentException("JWT Claims 검증/파싱 실패", e);
        }
    }
}
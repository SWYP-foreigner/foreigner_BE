package core.global.config;

import io.jsonwebtoken.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.security.Key;
import java.util.Date;

@Component
public class JwtTokenProvider {
    private final Key SECRET_KEY;
    private final int accessTokenExpiration;
    private final int refreshTokenExpiration;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secretKey,
            @Value("${jwt.access-expiration}") int accessTokenExpiration,
            @Value("${jwt.refresh-expiration}") int refreshTokenExpiration
    ) {
        this.SECRET_KEY = new SecretKeySpec(
                java.util.Base64.getDecoder().decode(secretKey),
                SignatureAlgorithm.HS512.getJcaName()
        );
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
    }

    /**
     * 액세스 토큰 생성
     * userId와 email을 Claims에 포함시킵니다.
     */
    public String createAccessToken(Long userId, String email) {
        Claims claims = Jwts.claims().setSubject(email);
        claims.put("id", userId); // userId를 Claims에 추가
        Date now = new Date();
        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + accessTokenExpiration * 60 * 1000L))
                .signWith(SECRET_KEY)
                .compact();
    }

    /** 리프레시 토큰 생성 */
    public String createRefreshToken(String email) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(email)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + refreshTokenExpiration * 60 * 1000L))
                .signWith(SECRET_KEY)
                .compact();
    }

    /** 토큰 검증 */
    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder().setSigningKey(SECRET_KEY).build().parseClaimsJws(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /** 이메일(subject) 추출 */
    public String getEmailFromToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(SECRET_KEY)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
    }

    /**
     * 액세스 토큰에서 userId 추출
     */
    public Long getUserIdFromAccessToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(SECRET_KEY)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .get("id", Long.class);
    }

    /** 만료시간 추출 */
    public Date getExpiration(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(SECRET_KEY)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getExpiration();
    }
}

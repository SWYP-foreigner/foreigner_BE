package core.global.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.security.Key;
import java.util.Date;

@Component
public class JwtTokenProvider {
    private final Key SECRET_KEY;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secretKeyBase64,
            @Value("${jwt.access-expiration}") long accessTokenExpiration,
            @Value("${jwt.refresh-expiration}") long refreshTokenExpiration
    ) {
        this.SECRET_KEY = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretKeyBase64));
        this.accessTokenExpiration = accessTokenExpiration * 60 * 1000L;
        this.refreshTokenExpiration = refreshTokenExpiration * 60 * 1000L;
    }

    /**
     * 액세스 토큰을 생성합니다.
     * userId와 email을 Claims에 포함시킵니다.
     */
    public String createAccessToken(Long userId, String email) {
        Claims claims = Jwts.claims().setSubject(email);
        claims.put("id", userId);
        Date now = new Date();
        Date expiration = new Date(now.getTime() + accessTokenExpiration);

        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(now)
                .setExpiration(expiration)
                .signWith(SECRET_KEY, SignatureAlgorithm.HS512)
                .compact();
    }

    /**
     * 리프레시 토큰을 생성합니다.
     * userId를 Claims와 Subject에 포함시킵니다.
     */
    public String createRefreshToken(Long userId) {
        Claims claims = Jwts.claims().setSubject(String.valueOf(userId));
        Date now = new Date();
        Date expiration = new Date(now.getTime() + refreshTokenExpiration);
        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(now)
                .setExpiration(expiration)
                .signWith(SECRET_KEY, SignatureAlgorithm.HS512)
                .compact();
    }
    /**
     * 토큰에서 이메일(subject)을 추출합니다.
     */
    public String getEmailFromToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(SECRET_KEY)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
    }

    /**
     * 액세스 토큰에서 사용자 ID를 추출합니다.
     */
    public Long getUserIdFromAccessToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(SECRET_KEY)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .get("id", Long.class);
    }

    /**
     * 토큰의 유효성을 검증합니다.
     * @return 유효하면 true, 아니면 false
     */
    public boolean validateToken(String token) {
        Jwts.parserBuilder().setSigningKey(SECRET_KEY).build().parseClaimsJws(token);
        return true;
    }
    /**
     * 토큰의 만료 시간을 추출합니다.
     */
    public Date getExpiration(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(SECRET_KEY)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getExpiration();
    }
    /**
     * 리프레시 토큰에서 사용자 ID를 추출합니다.
     */
    public Long getUserIdFromRefreshToken(String token) {
        return Long.valueOf(Jwts.parserBuilder()
                .setSigningKey(SECRET_KEY)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject());
    }

    /** ✅ 테스트용: 이메일만 받아 subject 로 넣는 심플 토큰 */
    public String createToken(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }
        Date now = new Date();
        Date expiration = new Date(now.getTime() + accessTokenExpiration);

        return Jwts.builder()
                .setSubject(email)          // subject = email
                .setIssuedAt(now)
                .setExpiration(expiration)
                .signWith(SECRET_KEY, SignatureAlgorithm.HS512)
                .compact();
    }
}
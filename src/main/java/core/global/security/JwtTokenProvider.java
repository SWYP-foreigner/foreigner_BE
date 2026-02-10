package core.global.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.global.enums.errorcode.AuthErrorCode;
import core.global.exception.BusinessException;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class JwtTokenProvider {

    private final Key SECRET_KEY;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;
    private final String keyHash;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secretKeyBase64,
            @Value("${jwt.access-expiration}") long accessTokenExpiration,
            @Value("${jwt.refresh-expiration}") long refreshTokenExpiration
    ) {
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(secretKeyBase64.trim());
        } catch (IllegalArgumentException e) {
            keyBytes = secretKeyBase64.getBytes(StandardCharsets.UTF_8);
        }
        this.SECRET_KEY = Keys.hmacShaKeyFor(keyBytes);
        this.accessTokenExpiration = accessTokenExpiration * 60 * 1000L;
        this.refreshTokenExpiration = refreshTokenExpiration * 60 * 1000L;

        this.keyHash = shortHash(keyBytes);
        log.info("[JWT] Provider init: alg=HS512, keyLen={}B, keyHash={}", keyBytes.length, this.keyHash);
    }

    // --- 1. 토큰 생성 메서드 ---

    public String createAccessToken(Long userId, String role, String email) {
        return buildToken(String.valueOf(email), userId, role, accessTokenExpiration);
    }

    public String createRefreshToken(Long userId) {
        // Refresh Token에는 role, email 등 민감정보 최소화 (필요시 추가)
        return buildToken(String.valueOf(userId), userId, null, refreshTokenExpiration);
    }

    private String buildToken(String subject, Long userId, String role, long expirationTime) {
        Claims claims = Jwts.claims().setSubject(subject);
        if (userId != null) claims.put("id", userId);
        if (role != null) claims.put("role", role);

        // JTI(JWT ID)는 토큰의 고유 식별자 (재사용 방지 등 목적)
        claims.setId(UUID.randomUUID().toString());

        Date now = new Date();
        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + expirationTime))
                .signWith(SECRET_KEY, SignatureAlgorithm.HS512)
                .compact();
    }

    // --- 2. 검증 및 파싱 (핵심) ---

    /**
     * 토큰 유효성 검증
     * ★ 중요: JwtTokenFilter에서 예외 종류(SignatureException vs Expired)를 구분해 로그를 찍기 위해,
     * 여기서 try-catch로 예외를 삼키지 않고 그대로 던집니다.
     */
    public boolean validateToken(String token) {
        parseClaims(token); // 파싱 실패 시 예외 발생 -> Filter로 전파
        return true;
    }

    /**
     * 공통 파싱 로직 (중복 제거)
     */
    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(SECRET_KEY)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    // --- 3. 정보 추출 메서드 ---

    public String getEmailFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    public Long getUserIdFromAccessToken(String token) {
        return parseClaims(token).get("id", Long.class);
    }

    public Long getUserIdFromRefreshToken(String token) {
        // RefreshToken은 subject 자체가 userId인 경우도 있고 claim에 있을 수도 있음. 로직에 맞춰 수정.
        // 위 createRefreshToken 기준으로는 subject에 userId가 들어감.
        String subject = parseClaims(token).getSubject();
        return Long.valueOf(subject);
    }

    public String getRoleFromToken(String token) {
        Object role = parseClaims(token).get("role");
        if (role == null) {
            throw new BadCredentialsException("Invalid token: Missing 'role' claim.");
        }
        return String.valueOf(role);
    }

    public Date getExpiration(String token) {
        return parseClaims(token).getExpiration();
    }

    // --- 4. 기타 유틸리티 ---

    public String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

    /**
     * (OIDC 등) 외부 공개키로 검증할 때 사용
     * 서비스 로직 내에서 사용되므로 BusinessException 처리 유지
     */
    public Claims getTokenClaims(String token, PublicKey publicKey) {
        try {
            return Jwts.parserBuilder() // parser() -> parserBuilder() (최신 버전 권장)
                    .setSigningKey(publicKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (io.jsonwebtoken.security.SignatureException | MalformedJwtException e) {
            throw new BusinessException(AuthErrorCode.INVALID_JWT);
        } catch (ExpiredJwtException e) {
            throw new BusinessException(AuthErrorCode.JWT_EXPIRED);
        }
    }

    public Map<String, String> parseHeaders(String token) throws JsonProcessingException {
        String header = token.split("\\.")[0];
        return new ObjectMapper().readValue(decodeHeader(header), Map.class);
    }

    private String decodeHeader(String token) {
        return new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
    }

    private static String shortHash(byte[] key) {
        int h = 1;
        for (byte b : key) h = 31 * h + (b & 0xff);
        return String.format("%08x", h);
    }

    public String keyHash() { return keyHash; }
}
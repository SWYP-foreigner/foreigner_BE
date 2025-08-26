package core.global.config;

import core.global.service.RedisService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenFilter extends OncePerRequestFilter {

    @Value("${jwt.secret}")
    private String secretKeyBase64;

    private final RedisService redisService; // Redis 연동 서비스

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    private static final List<String> EXCLUDE_URLS = List.of(
            "/api/v1/member/google/app-login",
            "/api/v1/member/google/callback",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/swagger-resources/**",
            "/swagger-ui.html"
    );

    /** JWT 서명 키 */
    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretKeyBase64));
    }

    /** 특정 요청은 필터링 제외 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        boolean isExcluded = EXCLUDE_URLS.stream().anyMatch(url -> pathMatcher.match(url, requestUri));
        log.debug("필터링 여부 확인: {}, 제외 여부 = {}", requestUri, isExcluded);
        return isExcluded;
    }

    /** JWT 필터 실행 */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        log.debug("--- JWT 필터 실행: 요청 URI = {} ---", request.getRequestURI());

        String auth = request.getHeader("Authorization");

        try {
            if (auth != null && auth.startsWith("Bearer ")) {
                String jwt = auth.substring(7);

                // ✅ 블랙리스트 체크
                if (redisService.isBlacklisted(jwt)) {
                    log.warn("블랙리스트에 등록된 토큰입니다. 요청 차단.");
                    throw new RuntimeException("Blacklisted token");
                }

                // ✅ JWT 파싱 및 검증
                Claims claims = Jwts.parserBuilder()
                        .setSigningKey(signingKey())
                        .build()
                        .parseClaimsJws(jwt)
                        .getBody();

                String subject = claims.getSubject();
                log.debug("토큰 검증 성공. subject = {}", subject);


                List<SimpleGrantedAuthority> authorities = new ArrayList<>();

                User principal = new User(subject, "", authorities);
                Authentication authentication =
                        new UsernamePasswordAuthenticationToken(principal, jwt, authorities);

                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.debug("SecurityContext에 인증 정보 저장 완료.");
            } else {
                log.debug("Authorization 헤더 없음 또는 Bearer 형식 아님 → 인증 처리 건너뜀.");
            }

            chain.doFilter(request, response);

        }catch (io.jsonwebtoken.ExpiredJwtException e) {
            log.warn("액세스 토큰이 만료되었습니다: {}", e.getMessage());
            SecurityContextHolder.clearContext();
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":\"token_expired\", \"message\":\"액세스 토큰이 만료되었습니다.\"}"
            );

        }
        catch (Exception e) {
            log.error("JWT 필터 처리 중 예외 발생: {}", e.getMessage(), e);
            SecurityContextHolder.clearContext();
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    String.format("{\"error\":\"invalid_token\", \"message\":\"%s\"}", e.getMessage())
            );
        }
    }
}

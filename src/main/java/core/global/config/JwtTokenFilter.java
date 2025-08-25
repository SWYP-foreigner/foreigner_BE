package core.global.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j; // Slf4j 로깅 라이브러리를 사용하기 위한 import
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.util.AntPathMatcher; // AntPathMatcher를 사용하기 위한 import

import javax.crypto.SecretKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j // 이 어노테이션이 있어야 log 객체를 사용할 수 있습니다.
public class JwtTokenFilter extends OncePerRequestFilter {

    @Value("${jwt.secret}")
    private String secretKeyBase64;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    private static final List<String> EXCLUDE_URLS = List.of(
            "/api/v1/member/google/app-login",
            "/health",
            "/api/v1//api/v1/member/**",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/swagger-resources/**",
            "/swagger-ui.html"
            // SecurityConfig에 명시된 다른 permitAll 경로들도 여기에 추가해야 합니다.
            // 예: "/api/v1/users/**", "/member/create" 등
    );

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretKeyBase64));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        log.info("필터링 여부 확인 중: 요청 URI = {}", requestUri);
        boolean isExcluded = EXCLUDE_URLS.stream().anyMatch(url -> pathMatcher.match(url, requestUri));
        log.info("요청 URI {}는 필터링 대상에서 제외되는가? -> {}", requestUri, isExcluded);
        return isExcluded;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        log.info("--- JWT 필터 실행: 요청 URI = {} ---", request.getRequestURI());

        String auth = request.getHeader("Authorization");

        try {
            if (auth != null && auth.startsWith("Bearer ")) {
                log.info("요청 헤더에서 토큰 발견. 토큰 검증 시작.");
                String jwt = auth.substring(7);

                Claims claims = Jwts.parserBuilder()
                        .setSigningKey(signingKey())
                        .build()
                        .parseClaimsJws(jwt)
                        .getBody();

                String subject = claims.getSubject();
                log.info("토큰 유효성 검증 성공. 사용자 주제(subject): {}", subject);

                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                Object role = claims.get("role");
                if (role != null) {
                    authorities.add(new SimpleGrantedAuthority(
                            role.toString().startsWith("ROLE_") ? role.toString() : "ROLE_" + role));
                }

                User principal = new User(subject, "", authorities);
                Authentication authentication =
                        new UsernamePasswordAuthenticationToken(principal, jwt, authorities);

                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.info("Security Context에 인증 정보 설정 완료.");
            } else {
                log.info("요청 헤더에 토큰이 없거나 'Bearer '로 시작하지 않음. 인증 정보 설정 안 함.");
            }

            log.info("--- JWT 필터 완료. 다음 필터로 진행합니다. ---");
            chain.doFilter(request, response);

        } catch (Exception e) {
            log.error("JWT 토큰 검증 중 예외 발생: {}", e.getMessage(), e);
            SecurityContextHolder.clearContext();
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(String.format("{\"error\":\"invalid token\", \"message\":\"%s\"}", e.getMessage()));
        }
    }
}

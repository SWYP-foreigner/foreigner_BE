package core.global.security;

import core.global.config.CustomUserDetails;
import core.global.enums.ErrorCode;
import core.global.redis.service.RedisService;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenFilter extends OncePerRequestFilter {

    @Value("${jwt.secret}")
    private String secretKeyBase64;
    private final JwtTokenProvider jwtTokenProvider;
    private final RedisService redisService;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    private static final String HEADER_PREFIX = "Bearer ";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        boolean shouldNotFilter = PermitAllPaths.PATTERNS.stream()
                .anyMatch(url -> pathMatcher.match(url, requestUri));

        if (requestUri.startsWith("/ws")) {
            log.info(">>>> [DEPLOYMENT CHECK] /ws request detected in shouldNotFilter. Result={}", shouldNotFilter);
        }
        return shouldNotFilter;
    }

    @PostConstruct
    public void init() {
        log.info("JwtTokenFilter 빈이 성공적으로 생성되었습니다.");
    }

    /** JWT 서명 키 */
    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretKeyBase64));
    }

    /** JWT 필터 실행 */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String requestUri = request.getRequestURI();

        // --- 🔽 1. [수정] 헤더(앱) 또는 쿠키(웹)에서 토큰 추출 🔽 ---
        String token = resolveToken(request);

        // --- 🔽 2. [수정] 토큰이 없는 경우 (가장 큰 변경점) 🔽 ---
        if (token == null) {
            log.trace("JWT 토큰 없음 (헤더 및 쿠키). URI={}", requestUri);
            // 토큰이 없으면, 인증 없이 다음 필터로 넘김.
            // SecurityConfig가 이 경로가 public인지 (permitAll)
            // 아니면 protected인지 (hasRole) 판단할 것임.
            // (기존의 jwtAuthenticationEntryPoint.commence() 호출 로직 삭제)
            chain.doFilter(request, response);
            return;
        }

        // --- 3. [유지] 토큰이 있는 경우, 기존 유효성 검사 로직 수행 ---
        try {
            if (redisService.isBlacklisted(token)) {
                log.warn("블랙리스트에 등록된 토큰입니다. URI={}", requestUri);
                jwtAuthenticationEntryPoint.commence(
                        request,
                        response,
                        new BadCredentialsException(ErrorCode.JWT_TOKEN_BLACKLISTED.getMessage())
                );
                return;
            }

            if (!jwtTokenProvider.validateToken(token)) {
                log.warn("유효하지 않은 JWT 토큰입니다. URI={}", requestUri);
                jwtAuthenticationEntryPoint.commence(
                        request,
                        response,
                        new BadCredentialsException(ErrorCode.JWT_TOKEN_INVALID.getMessage())
                );
                return;
            }

            String email = jwtTokenProvider.getEmailFromToken(token);
            Long userId = jwtTokenProvider.getUserIdFromAccessToken(token);
            String role = jwtTokenProvider.getRoleFromToken(token);

            if ("OUTCAST".equals(role)) {
                log.warn("Access denied for OUTCAST user. email={}", email);
                jwtAuthenticationEntryPoint.commence(
                        request,
                        response,
                        new BadCredentialsException(ErrorCode.JWT_INVAIL_ROLE.getMessage())
                );
                return;
            }

            List<GrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role)); // ROLE_USER, ROLE_ADMIN

            CustomUserDetails principal = new CustomUserDetails(userId, email, authorities);
            Authentication auth = new UsernamePasswordAuthenticationToken(principal, token, principal.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);

            log.debug("SecurityContext에 인증 정보 저장 완료. userId={}, email={}", userId, email);

            chain.doFilter(request, response);

        } catch (ExpiredJwtException e) {
            log.warn("JWT 토큰 만료: {}", e.getMessage());
            SecurityContextHolder.clearContext();
            jwtAuthenticationEntryPoint.commence(
                    request,
                    response,
                    new BadCredentialsException(ErrorCode.JWT_TOKEN_EXPIRED.getMessage())
            );
        } catch (Exception e) {
            log.error("JWT 필터 처리 중 예외 발생: {}", e.getMessage(), e);
            SecurityContextHolder.clearContext();
            jwtAuthenticationEntryPoint.commence(
                    request,
                    response,
                    new BadCredentialsException(ErrorCode.JWT_TOKEN_INVALID.getMessage())
            );
        }
    }

    /**
     * [신규] 헤더 또는 쿠키에서 토큰을 추출 (헤더 우선)
     */
    private String resolveToken(HttpServletRequest request) {
        String token = resolveTokenFromHeader(request);
        if (token == null) {
            token = resolveTokenFromCookie(request);
        }
        return token;
    }

    /**
     * [수정] 헤더에서 토큰 추출
     */
    private String resolveTokenFromHeader(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith(HEADER_PREFIX)) {
            return bearerToken.substring(HEADER_PREFIX.length());
        }
        return null;
    }

    /**
     * [신규] HttpOnly 쿠키에서 토큰 추출
     */
    private String resolveTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> "accessToken".equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}

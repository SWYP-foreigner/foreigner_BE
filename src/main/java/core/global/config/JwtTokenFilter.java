package core.global.config;

import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import core.global.service.RedisService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
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
    private final JwtTokenProvider jwtTokenProvider;
    private final RedisService redisService;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    private static final List<String> EXCLUDE_URLS = List.of(
            "/api/v1/member/google/app-login",
            "/api/v1/member/google/**",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/swagger-resources/**",
            "/swagger-ui.html",
            "/auth/**",
            "/api/v1/member/signup",
            "/api/v1/member/doLogin",
            "/api/v1/member/verify-code",
            "/api/v1/member/signup",
            "/api/v1/member/send-verification-email",
            "/api/v1/member/refresh",
            "/swagger-ui.html",
            "/api/v1/member/password/**",
            "/ws/**"
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        return EXCLUDE_URLS.stream().anyMatch(url -> pathMatcher.match(url, requestUri));
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

        String authHeader = request.getHeader("Authorization");
        String requestUri = request.getRequestURI();

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Authorization 헤더가 없거나 Bearer 형식이 아님 URI={}", requestUri);
            jwtAuthenticationEntryPoint.commence(
                    request,
                    response,
                    new BadCredentialsException(ErrorCode.JWT_TOKEN_NOT_FOUND.getMessage())
            );
            return;
        }

        String token = authHeader.substring(7);

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

            // 토큰에서 정보 추출
            String email = jwtTokenProvider.getEmailFromToken(token);
            Long userId = jwtTokenProvider.getUserIdFromAccessToken(token);


            CustomUserDetails principal = new CustomUserDetails(userId, email, new ArrayList<>());

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
}

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
        /**
         * 인터셉터 : 사용자가 설정에 따라
         * 컨트롤러로 넘어가는 구조로 만든다.
         * 시큐리티
         */
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
                // accessToken 은 사라질 수가 없다. accessToken (로그아웃 +회원 탈퇴) 현재의 accessToken
                //

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
                log.warn("Access denied for OUTCAST user. email={}");
                jwtAuthenticationEntryPoint.commence(
                        request,
                        response,
                        new BadCredentialsException(ErrorCode.JWT_INVAIL_ROLE.getMessage())
                );
                return;
            }
            List<GrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role)); // ROLE_USER 등

            CustomUserDetails principal = new CustomUserDetails(userId, email, authorities);
            Authentication auth = new UsernamePasswordAuthenticationToken(principal, token, principal.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);

            log.debug("SecurityContext에 인증 정보 저장 완료. userId={}, email={}", userId, email);

            chain.doFilter(request, response);


        } catch (ExpiredJwtException e) {
            log.warn("JWT 토큰 만료: {}", e.getMessage());
            // 프론트에 인증객체가 삭제 돼었느 걸
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

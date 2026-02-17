package core.global.security;

import core.global.config.CustomUserDetails;
import core.global.enums.errorcode.AuthErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.redis.service.RedisService;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
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

    // EntryPoint는 여기서 직접 쓰지 않고 에러 속성만 넘기므로 제거해도 되지만,
    // 혹시 다른 용도로 필요하다면 유지하세요. (현재 로직엔 불필요)
    // private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    private static final String HEADER_PREFIX = "Bearer ";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getRequestURI();

        // [중요 변경]
        // 기존에는 PermitAll 경로면 필터를 아예 건너뛰게(return true) 설정하셨을 수 있습니다.
        // 하지만 "토큰이 있으면 검증하고, 없거나 이상하면 익명으로 처리"하는 로직이 더 안전하고 유연합니다.
        // 따라서 특정 시스템 경로(/ws 등) 외에는 필터를 타게 하는 것이 좋습니다.

        if (requestUri.startsWith("/ws")) {
            log.trace(">>>> [DEPLOYMENT CHECK] /ws request detected in shouldNotFilter.");
            // 웹소켓 등 특수 경로는 필요에 따라 제외
            return false;
        }

        // 기본적으로 모든 요청에 대해 필터를 실행하여 토큰 여부를 확인합니다.
        // (토큰이 없거나 만료되어도 아래 doFilterInternal에서 안전하게 처리됨)
        return false;
    }

    @PostConstruct
    public void init() {
        log.info("JwtTokenFilter 빈이 성공적으로 생성되었습니다.");
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretKeyBase64));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String requestUri = request.getRequestURI();
        String clientIp = getClientIp(request);
        String token = resolveToken(request);

        // 1. 토큰이 없는 경우 -> 익명 사용자로 다음 필터 진행
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }

        // 2. 토큰이 있는 경우 -> 검증 시도
        try {
            // (1) 블랙리스트 확인
            if (redisService.isBlacklisted(token)) {
                log.warn("[SECURITY WARN] 블랙리스트 토큰 접근. IP: {}, URI: {}", clientIp, requestUri);
                // 예외를 던져서 catch 블록으로 보냄 (바로 응답 X)
                throw new io.jsonwebtoken.security.SecurityException("Blacklisted Token");
            }

            // (2) 토큰 유효성 검사
            if (!jwtTokenProvider.validateToken(token)) {
                log.warn("[AUTH FAIL] 유효하지 않은 토큰. IP: {}, URI: {}", clientIp, requestUri);
                throw new io.jsonwebtoken.security.SecurityException("Invalid Token");
            }

            // (3) 사용자 정보 추출
            String email = jwtTokenProvider.getEmailFromToken(token);
            Long userId = jwtTokenProvider.getUserIdFromAccessToken(token);
            String role = jwtTokenProvider.getRoleFromToken(token);

            // (4) 역할 검증 (OUTCAST 등)
            if ("OUTCAST".equals(role)) {
                log.warn("[ACCESS DENIED] OUTCAST user. email={}, IP={}", email, clientIp);
                // UserErrorCode 처리를 위해 메시지를 넘김
                request.setAttribute("exception", UserErrorCode.JWT_INVALID_ROLE.name());
                throw new io.jsonwebtoken.security.SecurityException("Outcast User");
            }

            // (5) 인증 객체 생성 (성공 시)
            List<GrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));

            CustomUserDetails principal = new CustomUserDetails(userId, email, authorities);
            Authentication auth = new UsernamePasswordAuthenticationToken(principal, token, principal.getAuthorities());

            // SecurityContext에 저장
            SecurityContextHolder.getContext().setAuthentication(auth);
            log.debug("[AUTH SUCCESS] User: {}, URI: {}", email, requestUri);

        } catch (ExpiredJwtException e) {
            // [핵심 수정] 만료된 토큰이어도 에러 응답을 보내지 않고 '메모'만 함
            log.info("[AUTH INFO] 만료된 토큰입니다. 익명으로 진행합니다. IP: {}, URI: {}", clientIp, requestUri);
            request.setAttribute("exception", AuthErrorCode.JWT_TOKEN_EXPIRED.name());
            SecurityContextHolder.clearContext(); // 인증 정보 확실히 제거

        } catch (io.jsonwebtoken.security.SignatureException e) {
            log.error("[SECURITY ATTACK SUSPECTED] 서명 불일치. IP: {}, URI: {}", clientIp, requestUri);
            request.setAttribute("exception", AuthErrorCode.JWT_TOKEN_INVALID.name());
            SecurityContextHolder.clearContext();

        } catch (MalformedJwtException e) {
            log.error("[SECURITY ATTACK SUSPECTED] 손상된 토큰. IP: {}, URI: {}", clientIp, requestUri);
            request.setAttribute("exception", AuthErrorCode.JWT_TOKEN_INVALID.name());
            SecurityContextHolder.clearContext();

        } catch (UnsupportedJwtException e) {
            log.warn("[AUTH WARN] 지원하지 않는 토큰 형식. IP: {}, URI: {}", clientIp, requestUri);
            request.setAttribute("exception", AuthErrorCode.JWT_TOKEN_INVALID.name());
            SecurityContextHolder.clearContext();

        } catch (Exception e) {
            log.error("[AUTH ERROR] 알 수 없는 오류. IP: {}, Error: {}", clientIp, e.getMessage());
            request.setAttribute("exception", AuthErrorCode.JWT_TOKEN_INVALID.name());
            SecurityContextHolder.clearContext();
        }

        // [핵심 수정] 토큰이 유효하든 아니든 무조건 다음 필터로 넘깁니다.
        // SecurityConfig가 requestMatcher 설정을 보고 통과시킬지(PermitAll) 막을지(401) 결정합니다.
        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String token = resolveTokenFromHeader(request);
        if (token == null) {
            token = resolveTokenFromCookie(request);
        }
        return token;
    }

    private String resolveTokenFromHeader(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith(HEADER_PREFIX)) {
            return bearerToken.substring(HEADER_PREFIX.length());
        }
        return null;
    }

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

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) ip = request.getHeader("Proxy-Client-IP");
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) ip = request.getHeader("WL-Proxy-Client-IP");
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) ip = request.getHeader("HTTP_CLIENT_IP");
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) ip = request.getRemoteAddr();
        return ip;
    }

    private String getUserAgent(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        return ua != null ? ua : "Unknown";
    }
}
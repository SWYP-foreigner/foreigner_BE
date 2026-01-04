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
import io.jsonwebtoken.security.SignatureException; // 중요: 서명 예외
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
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    private static final String HEADER_PREFIX = "Bearer ";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        boolean shouldNotFilter = PermitAllPaths.PATTERNS.stream()
                .anyMatch(url -> pathMatcher.match(url, requestUri));

        if (requestUri.startsWith("/ws")) {
            log.trace(">>>> [DEPLOYMENT CHECK] /ws request detected in shouldNotFilter. Result={}", shouldNotFilter);
        }
        return shouldNotFilter;
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
        String userAgent = getUserAgent(request);
        String token = resolveToken(request);

        // 토큰이 아예 없는 경우 (익명 사용자 허용 경로 or 401 처리될 예정)
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }

        try {
            // 1. 블랙리스트 확인 (로그아웃된 토큰 사용 시도)
            if (redisService.isBlacklisted(token)) {
                log.warn("[SECURITY WARN] 블랙리스트 토큰 접근 시도. IP: {}, URI: {}, UA: {}", clientIp, requestUri, userAgent);
                handleAuthError(request, response, AuthErrorCode.JWT_TOKEN_BLACKLISTED);
                return;
            }

            // 2. 토큰 유효성 검사 (여기서 예외가 발생하면 catch로 이동)
            // 주의: validateToken이 내부에서 예외를 삼키고 false만 리턴한다면 예외를 밖으로 던지도록 수정하거나,
            // 파싱 로직을 여기서 수행해야 정확한 예외 로그를 찍을 수 있습니다.
            // 아래 코드는 validateToken이 boolean을 반환한다고 가정했을 때의 방어 로직입니다.
            if (!jwtTokenProvider.validateToken(token)) {
                // validateToken이 예외를 던지지 않고 false를 리턴했다면, 구체적인 원인을 알 수 없으므로 일반 경고 처리
                log.warn("[AUTH FAIL] 유효하지 않은 JWT 토큰 (세부 원인 불명). IP: {}, URI: {}", clientIp, requestUri);
                handleAuthError(request, response, AuthErrorCode.JWT_TOKEN_INVALID);
                return;
            }

            // 3. 사용자 정보 추출
            String email = jwtTokenProvider.getEmailFromToken(token);
            Long userId = jwtTokenProvider.getUserIdFromAccessToken(token);
            String role = jwtTokenProvider.getRoleFromToken(token);

            // 4. 역할 검증
            if ("OUTCAST".equals(role)) {
                log.warn("[ACCESS DENIED] OUTCAST user access attempt. email={}, IP={}", email, clientIp);
                handleAuthError(request, response, UserErrorCode.JWT_INVALID_ROLE); // ErrorCode 타입 맞추기 필요
                return;
            }

            // 5. 인증 객체 생성
            List<GrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));

            CustomUserDetails principal = new CustomUserDetails(userId, email, authorities);
            Authentication auth = new UsernamePasswordAuthenticationToken(principal, token, principal.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);

            log.debug("[AUTH SUCCESS] User: {}, URI: {}", email, requestUri);

            chain.doFilter(request, response);

        } catch (io.jsonwebtoken.security.SignatureException e) {
            // ★ 중요: 서명이 다르다는 건 위변조 시도거나 서버 키 변경됨
            log.error(">>>> [SECURITY ATTACK SUSPECTED] JWT 서명 불일치! 토큰 위변조 가능성 있음. IP: {}, URI: {}, UA: {}, Error: {}",
                    clientIp, requestUri, userAgent, e.getMessage());
            handleAuthError(request, response, AuthErrorCode.JWT_TOKEN_INVALID);

        } catch (MalformedJwtException e) {
            // ★ 중요: 토큰 구조가 깨짐 (해커가 랜덤 값을 넣어보는 퍼징 공격일 수 있음)
            log.error(">>>> [SECURITY ATTACK SUSPECTED] 손상된 JWT 토큰. IP: {}, URI: {}, UA: {}",
                    clientIp, requestUri, userAgent);
            handleAuthError(request, response, AuthErrorCode.JWT_TOKEN_INVALID);

        } catch (ExpiredJwtException e) {
            // 만료는 공격이라기보다 자연스러운 현상 (로그 레벨 INFO or WARN)
            log.info("[AUTH INFO] 만료된 JWT 토큰입니다. IP: {}, URI: {}", clientIp, requestUri);
            handleAuthError(request, response, AuthErrorCode.JWT_TOKEN_EXPIRED);

        } catch (UnsupportedJwtException e) {
            log.warn("[AUTH WARN] 지원하지 않는 JWT 토큰 형식. IP: {}, URI: {}", clientIp, requestUri);
            handleAuthError(request, response, AuthErrorCode.JWT_TOKEN_INVALID);

        } catch (Exception e) {
            log.error("[AUTH ERROR] JWT 처리 중 알 수 없는 오류 발생. IP: {}, URI: {}, Error: {}", clientIp, requestUri, e.getMessage());
            handleAuthError(request, response, AuthErrorCode.JWT_TOKEN_INVALID);
        }
    }

    /**
     * 예외 처리 공통 메서드
     */
    private void handleAuthError(HttpServletRequest request, HttpServletResponse response, AuthErrorCode errorCode) throws IOException, ServletException {
        handleAuthError(request, response, errorCode.getMessage());
    }

    // UserErrorCode 등 다른 Enum 처리를 위한 오버로딩 (필요 시 수정)
    private void handleAuthError(HttpServletRequest request, HttpServletResponse response, UserErrorCode errorCode) throws IOException, ServletException {
        handleAuthError(request, response, errorCode.getMessage());
    }

    private void handleAuthError(HttpServletRequest request, HttpServletResponse response, String message) throws IOException, ServletException {
        SecurityContextHolder.clearContext();
        jwtAuthenticationEntryPoint.commence(
                request,
                response,
                new BadCredentialsException(message)
        );
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

    /**
     * 클라이언트 IP 추출 (Proxy, LB 환경 고려)
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    /**
     * User-Agent 추출 (브라우저/기기 정보)
     */
    private String getUserAgent(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        return ua != null ? ua : "Unknown";
    }
}
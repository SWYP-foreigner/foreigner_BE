package core.global.config;

import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import core.global.service.RedisService;
import io.jsonwebtoken.Claims;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
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
@EnableWebSecurity
public class JwtTokenFilter extends OncePerRequestFilter {

    @Value("${jwt.secret}")
    private String secretKeyBase64;

    private final RedisService redisService; // Redis 연동 서비스

    private final AntPathMatcher pathMatcher = new AntPathMatcher();


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

        log.debug("--- JWT 필터 실행: 요청 URI = {} ---", request.getRequestURI());

        String auth = request.getHeader("Authorization");

        if (auth == null || !auth.startsWith("Bearer ")) {
            log.warn("인증 정보가 없습니다. 요청을 차단합니다. URI = {}", request.getRequestURI());
            throw new BusinessException(ErrorCode.JWT_TOKEN_NOT_FOUND);
        }

        try {
            if (auth != null && auth.startsWith("Bearer ")) {
                String jwt = auth.substring(7);

                if (redisService.isBlacklisted(jwt)) {
                    log.warn("블랙리스트에 등록된 토큰입니다. 요청 차단.");
                    throw new BusinessException(ErrorCode.JWT_TOKEN_BLACKLISTED);
                }
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
            throw new BusinessException(ErrorCode.JWT_TOKEN_EXPIRED);
        } catch (Exception e) {
            log.error("JWT 필터 처리 중 예외 발생: {}", e.getMessage(), e);
            SecurityContextHolder.clearContext();
            throw new BusinessException(ErrorCode.JWT_TOKEN_INVALID);
        }
    }
}

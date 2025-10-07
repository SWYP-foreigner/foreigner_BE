package core.global.metrics;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class PresenceActivityFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redis;
    // 활동 유지 TTL(초). 예: 최근 5분 내 활동이면 "접속 중"으로 간주
    private static final long TTL_SECONDS = 300;
    private static final String ZKEY = "presence:active:zset"; // score = 만료 타임스탬프(ms)

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        org.springframework.security.core.Authentication auth =
                SecurityContextHolder.getContext().getAuthentication();

        if (auth != null && auth.isAuthenticated() && auth.getName() != null) {
            String uid = auth.getName(); // 가능하면 내부 userId 문자열 사용
            long now = System.currentTimeMillis();
            long expireAt = now + TTL_SECONDS * 1000;

            // 만료시각을 score로 저장(멤버=uid). upsert 형태
            redis.opsForZSet().add(ZKEY, uid, expireAt);
        }
        chain.doFilter(req, res);
    }

}


package core.global.service;

import core.global.service.AuthUtils;
import core.global.service.EventsIndexService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class ActiveHeartbeatFilter extends OncePerRequestFilter {

    private final EventsIndexService eventsIndexService;

    private final AntPathMatcher pm = new AntPathMatcher();
    private static final DateTimeFormatter MIN_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm").withZone(ZoneOffset.UTC);

    // key = userId + ":" + minuteKey(예: 20250926T1013)
    private final Cache<String, Boolean> sentCache = Caffeine.newBuilder()
            .maximumSize(200_000)                // 서비스 크기에 맞춰 조정
            .expireAfterWrite(70, TimeUnit.SECONDS) // 1분 버킷 + 여유
            .build();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return pm.match("/actuator/**", p)
               || pm.match("/health/**", p)
               || pm.match("/prometheus/**", p)
               || pm.match("/static/**", p)
               || pm.match("/favicon.ico", p);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        try {
            String userId = AuthUtils.currentUserIdOrNull(); // 현재 인증 사용자(이메일)
            if (userId != null) {
                String minuteKey = MIN_FMT.format(Instant.now());
                String cacheKey = userId + ":" + minuteKey;
                Boolean already = sentCache.getIfPresent(cacheKey);
                if (already == null) {
                    sentCache.put(cacheKey, Boolean.TRUE);
                    eventsIndexService.logActive(userId, deviceFromUA(req)); // 비동기 아님: ES 클라이언트 내부에서 네트워크
                }
            }
        } catch (Exception ignore) {
            // 실패해도 요청 흐름 영향 X
        }

        chain.doFilter(req, res);
    }

    private String deviceFromUA(HttpServletRequest req) {
        String ua = req.getHeader("User-Agent");
        if (ua == null) return null;
        String s = ua.toLowerCase();
        if (s.contains("android")) return "google";
        if (s.contains("iphone") || s.contains("ipad")) return "apple";
        return "email";
    }
}

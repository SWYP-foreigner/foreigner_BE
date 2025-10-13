package core.global.log;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AccessLogFilter extends OncePerRequestFilter {
    private static final Logger ACCESS = LoggerFactory.getLogger("ACCESS");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, java.io.IOException {
        long start = System.currentTimeMillis();
        try { chain.doFilter(req, res); }
        finally {
            long took = System.currentTimeMillis() - start;
            MDC.put("method", req.getMethod());
            MDC.put("path", req.getRequestURI());
            MDC.put("status", String.valueOf(res.getStatus()));
            MDC.put("latency_ms", String.valueOf(took));
            ACCESS.info("access"); // 필드는 MDC로 JSON에 들어감
            MDC.clear();
        }
    }
}

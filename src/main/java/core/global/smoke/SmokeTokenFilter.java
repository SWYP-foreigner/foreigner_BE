package core.global.smoke;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class SmokeTokenFilter extends OncePerRequestFilter {

    @Value("${smoke.enabled:false}")
    private boolean enabled;

    @Value("${smoke.token:}")
    private String smokeToken;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) return true;
        String uri = request.getRequestURI();
        // /internal/smoke만 보호
        return !uri.startsWith("/internal/smoke");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        if (smokeToken == null || smokeToken.isBlank()) {
            res.setStatus(503);
            res.setContentType(MediaType.TEXT_PLAIN_VALUE);
            res.getWriter().write("Smoke token not configured");
            return;
        }

        String header = req.getHeader("X-Smoke-Token");
        if (header == null || !smokeToken.equals(header)) {
            res.setStatus(401);
            res.setContentType(MediaType.TEXT_PLAIN_VALUE);
            res.getWriter().write("Unauthorized");
            return;
        }

        chain.doFilter(req, res);
    }
}

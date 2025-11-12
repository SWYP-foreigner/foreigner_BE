package core.global.handler;

import core.global.exception.UserErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class VisitorUserGuardAccessDeniedHandler implements AccessDeniedHandler {

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final List<String> userOnlyPatterns;
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public VisitorUserGuardAccessDeniedHandler(List<String> userOnlyPatterns) {
        this.userOnlyPatterns = userOnlyPatterns;
    }

    private boolean isUserOnlyPath(String uri) {
        for (String p : userOnlyPatterns) {
            if (matcher.match(p, uri)) return true;
        }
        return false;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException ex) throws IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String uri = request.getRequestURI();

        boolean isVisitor = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_VISITOR".equals(a.getAuthority()));

        response.setContentType("application/json;charset=UTF-8");
        String now = LocalDateTime.now().format(TS_FMT);

        if (isVisitor && isUserOnlyPath(uri)) {
            // 428 Precondition Required
            response.setStatus(428);
            String body = """
                    {"error":"%s","message":"%s","timestamp":"%s"}
                    """.formatted(
                    UserErrorCode.PROFILE_SET_NOT_COMPLETED.getMessage(), // "프로필이 완성되지 않았습니다."
                    "428",
                    now
            );
            response.getWriter().write(body);
            return;
        }

        // 기본 403
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        String body = """
                {"error":"%s","message":"%s","timestamp":"%s"}
                """.formatted(
                "접근이 거부되었습니다.",
                String.valueOf(HttpServletResponse.SC_FORBIDDEN),
                now
        );
        response.getWriter().write(body);
    }
}

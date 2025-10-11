package core.global.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.util.List;

public class VisitorUserGuardAccessDeniedHandler implements AccessDeniedHandler {

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final List<String> userOnlyPatterns;

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

        if (isVisitor && isUserOnlyPath(uri)) {
            response.setStatus(428); // Precondition Required
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"PROFILE_SET_NOT_COMPLETED\"}");
            return;
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"ACCESS_DENIED\"}");
    }
}
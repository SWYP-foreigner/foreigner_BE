package core.global.constants;

import java.util.List;

public final class AdminOnlyPaths {
    private AdminOnlyPaths() {}

    public static final List<String> PATTERNS = List.of(
            "/api/v1/admin/**",
            "/swagger-resources/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/api/v1/images/object",
            "/api/v1/images/delete-folder",
            "/api/v2/poll/quiz/**"
    );
}
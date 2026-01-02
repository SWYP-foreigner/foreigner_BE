package core.global.security;

import java.util.List;

public final class AdminOnlyPaths {

    public static final List<String> PATTERNS = List.of(
            "/api/v1/admin/**",
            "/admin/**",
            "/api/v1/docs/**"
    );
    private AdminOnlyPaths() { }
}
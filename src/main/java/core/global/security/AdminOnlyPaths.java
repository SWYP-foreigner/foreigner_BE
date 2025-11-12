package core.global.security;

import java.util.List;

public final class AdminOnlyPaths {

    public static final List<String> PATTERNS = List.of(
            "/api/v1/admin/**",
            "/api/v1/manage/users"
    );
    private AdminOnlyPaths() { }
}
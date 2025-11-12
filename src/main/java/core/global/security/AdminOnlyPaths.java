package core.global.security;

import java.util.List;

public final class AdminOnlyPaths {

    public static final List<String> PATTERNS = List.of(
            "/admin/**"
    );
    private AdminOnlyPaths() { }
}
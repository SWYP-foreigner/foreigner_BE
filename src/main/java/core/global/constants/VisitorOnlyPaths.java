package core.global.constants;

import java.util.List;

public final class VisitorOnlyPaths {
    private VisitorOnlyPaths() {}

    public static final List<String> PATTERNS = List.of(
            "/api/v1/mypage/profile/skip-setup",

            "/api/v1/commend/content-based",
            "/api/v1/search/**",

            "/api/v1/chat/group/**",
            "/api/v1/chat/rooms/group/**",
            "/api/v1/chat/rooms/search",
            "/api/v1/chat/rooms"
    );
}
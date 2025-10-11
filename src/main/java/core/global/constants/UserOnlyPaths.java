package core.global.constants;

import java.util.List;

public final class UserOnlyPaths {
    // ✅ USER 전용 경로는 여기만 수정하면 됩니다.
    public static final List<String> PATTERNS = List.of(
            "/api/v1/member/**",
            "/api/v1/mypage/**",
            "/api/v1/posts/**",
            "/api/v1/boards/**",
            "/api/v1/users/**",
            "/api/v1/mypage/**",
            "/api/v1/my/**",
            "/api/v1/comments/**",
            "/api/v1/chat/**",
            "/api/v1/images/chat-rooms"
    );

    private UserOnlyPaths() {
    }
}

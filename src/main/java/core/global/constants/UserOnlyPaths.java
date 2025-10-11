package core.global.constants;

import java.util.List;

public final class UserOnlyPaths {
    private UserOnlyPaths() {}

    // ✅ USER 전용 경로는 여기만 수정하면 됩니다.
    public static final List<String> PATTERNS = List.of(
            "/api/v1/member/profile/**",
            "/api/v1/mypage/profile/**",
            "/api/v1/board/**",
            "/api/v1/users/**",
            "/api/v1/mypage/**"
    );
}

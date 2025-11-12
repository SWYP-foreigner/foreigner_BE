package core.global.constants;

import java.util.List;

public final class PermitAllPaths {
    private PermitAllPaths() {}

    public static final List<String> PATTERNS = List.of(
            "/api/v1/member/google/app-login",
            "/api/v1/member/apple/app-login",
            "/api/v1/member/doLogin",
            "/api/v1/member/signup",
            "/api/v1/member/verify-code",
            "/api/v1/member/send-verification-email",
            "/api/v1/member/password/**",
            "/api/v1/member/email/check",
            "/api/v1/member/refresh",
            "/api/v1/images/presign",
            "/actuator/**",
            "/error", "/error/**",
            "/ws/**", "/ws"
    );
}

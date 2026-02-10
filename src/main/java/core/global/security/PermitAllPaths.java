package core.global.security;

import java.util.List;

public final class PermitAllPaths {

    public static final List<String> PATTERNS = List.of(
            "/api/v1/member/google/app-login",
            "/api/v1/member/google/**",
            "/api/v1/member/apple/app-login",
            "/api/v1/member/apple/**",
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
            "/ws/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/swagger-resources/**",
            "/admin/login",
            "/api/v1/member/admin/login",
            "/api/v1/member/admin/otp-verify",
            "/api/v1/member/admin/otp/reset-request",
            "/api/v1/member/admin/otp/reset-confirm",
            "/admin/test/send-message",
            "/api/v1/app/**",
            "/internal/smoke"
    );

    private PermitAllPaths() { }
}
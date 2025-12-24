package core.global.admin.controller;

import core.global.smoke.dto.SmokeResult;
import core.global.smoke.runner.SmokeGateRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/health-check")
@RequiredArgsConstructor
public class AdminHealthCheckController {

    private final SmokeGateRunner runner;
    private final OAuthSmokeCheckService oAuthSmokeCheckService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public String healthCheckPage() {
        return "admin/health-check"; // templates/admin/health-check.html 파일
    }

    @PostMapping("/full-check")
    @PreAuthorize("hasRole('ADMIN')") // 관리자만 가능
    public ResponseEntity<SmokeResult> runFullCheck() {
        // 관리자는 항상 'full' 모드로 전체 점검 수행
        SmokeResult result = runner.run("full");
        return ResponseEntity.ok(result);
    }

    @GetMapping("/debug/oauth/google")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> checkGoogleSecret() {
        try {
            String result = oAuthSmokeCheckService.googleChecker();
            return ResponseEntity.ok(result); // 성공 시 200
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(e.getMessage()); // 실패 시 401
        }
    }

    @GetMapping("/debug/oauth/apple")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> checkAppleSecret() {
        try {
            String result = oAuthSmokeCheckService.appleChecker();
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(e.getMessage());
        }
    }
}
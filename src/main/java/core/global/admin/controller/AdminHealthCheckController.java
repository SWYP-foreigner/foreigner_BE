package core.global.admin.controller;

import core.global.smoke.runner.SmokeGateRunner;
import core.global.smoke.dto.SmokeResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/health-check")
@RequiredArgsConstructor
public class AdminHealthCheckController {

    private final SmokeGateRunner runner;

    @PostMapping("/full-check")
    @PreAuthorize("hasRole('ADMIN')") // 관리자만 가능
    public ResponseEntity<SmokeResult> runFullCheck() {
        // 관리자는 항상 'full' 모드로 전체 점검 수행
        SmokeResult result = runner.run("full");
        return ResponseEntity.ok(result);
    }
}
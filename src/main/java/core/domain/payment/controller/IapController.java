package core.domain.payment.controller;

import core.domain.payment.dto.EntitlementResponse;
import core.domain.payment.dto.VerifyRequest;
import core.domain.payment.service.IapService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/iap")
public class IapController {
    private final IapService iapService;

    public IapController(IapService iapService) {
        this.iapService = iapService;
    }

    @Operation(summary = "스토어 서버 검증 + 권한 계산/저장", description = "iOS는 transactionId, Android는 purchaseToken(+productId) 필수")
    @PostMapping("/verify")
    public EntitlementResponse verify(@RequestBody @Valid VerifyRequest req) {
        return iapService.verify(req);
    }

    @Operation(summary = "현재 권한 스냅샷 조회", description = "앱 런치/포그라운드 시 주기 조회")
    @GetMapping("/entitlements")
    public EntitlementResponse ent() {
        return iapService.getEntitlements();
    }
}
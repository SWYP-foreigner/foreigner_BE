package core.domain.payment.controller;

import core.domain.payment.service.GoogleIapWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/iap/google")
public class GoogleIapWebhookController {

    private final GoogleIapWebhookService googleIapWebhookService;

    public GoogleIapWebhookController(GoogleIapWebhookService googleIapWebhookService) {
        this.googleIapWebhookService = googleIapWebhookService;
    }

    @Operation(summary = "Google RTDN 수신", description = "Pub/Sub Push message.data(Base64) 디코드 후 재조회")
    @PostMapping("/rtdn")
    public void handleGoogleRtdn(@RequestBody Map<String, Object> pushEnvelope) {
        googleIapWebhookService.handleGoogleRtdn(pushEnvelope);
    }
}
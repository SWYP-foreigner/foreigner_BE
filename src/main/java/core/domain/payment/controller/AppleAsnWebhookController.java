package core.domain.payment.controller;

import core.domain.payment.service.AppleAsnWebhookService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/iap/apple")
public class AppleAsnWebhookController {
    private final AppleAsnWebhookService webhookService;

    public AppleAsnWebhookController(AppleAsnWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/asnv2")
    public void handleAppleAsnV2(@RequestBody Map<String, Object> requestBody) throws Exception {
        webhookService.handleAppleAsnV2(requestBody);
    }
}
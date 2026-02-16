package core.domain.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.domain.payment.dto.VerifyRequest;
import core.domain.payment.entity.IapWebhookEvent;
import core.domain.payment.repository.IapWebhookEventRepository;
import core.global.enums.DeviceType;
import core.global.enums.errorcode.PaymentErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GoogleIapWebhookService {

    private final IapService iapService;
    private final IapWebhookEventRepository iapWebhookEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void handleGoogleRtdn(Map<String, Object> pushEnvelope) {
        Object messageObj = pushEnvelope.get("message");
        if (!(messageObj instanceof Map<?, ?> rawMessage)) {
            return; // 형식이 안 맞으면 그냥 무시
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) rawMessage;
        if (message.get("data") == null) return;

        String messageId = String.valueOf(message.get("messageId"));
        String base64Payload = String.valueOf(message.get("data"));
        String json = new String(Base64.getDecoder().decode(base64Payload), StandardCharsets.UTF_8);

        var event = iapWebhookEventRepository.findByPlatformAndDedupKey(DeviceType.ANDROID, messageId)
                .orElseGet(() -> new IapWebhookEvent(DeviceType.ANDROID, messageId, "RTDN", "PENDING", json));
        if ("DONE".equals(event.getProcessStatus())) return;

        try {
            JsonNode root = objectMapper.readTree(json);
            String purchaseToken = null;
            String productId = null;

            if (root.has("subscriptionNotification")) {
                JsonNode sn = root.get("subscriptionNotification");
                purchaseToken = sn.path("purchaseToken").asText(null);
                productId = sn.path("subscriptionId").asText(null);
            } else if (root.has("oneTimeProductNotification")) {
                JsonNode on = root.get("oneTimeProductNotification");
                purchaseToken = on.path("purchaseToken").asText(null);
                productId = on.path("sku").asText(null);
            } else if (root.has("testNotification")) {
                event.updateProcessStatus("DONE");
                return;
            }

            if (purchaseToken != null) {
                VerifyRequest verifyRequest = new VerifyRequest("android", null, purchaseToken, productId);
                iapService.verify(verifyRequest);
            }
            event.updateProcessStatus("DONE");
        } catch (Exception ex) {
            event.updateProcessStatus("FAILED");
            throw new BusinessException(PaymentErrorCode.GOOGLE_WEBHOOK_FAILED, ex);
        }
    }
}

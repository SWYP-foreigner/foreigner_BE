package core.domain.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.domain.payment.dto.VerifyRequest;
import core.domain.payment.entity.IapWebhookEvent;
import core.domain.payment.repository.IapWebhookEventRepository;
import core.global.enums.DeviceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AppleAsnWebhookService {

    private final IapService iapService;
    private final IapWebhookEventRepository iapWebhookEventRepository;
    private final ObjectMapper objectMapper;
    private final AppleJwsVerifier appleJwsVerifier;

    @Transactional
    public void handleAppleAsnV2(Map<String, Object> requestBody) {
        String signedPayload = Objects.toString(requestBody.get("signedPayload"), null);
        if (signedPayload == null) return;

        // 1) JWS 검증 → JSON
        String payload = appleJwsVerifier.verifyAndGetPayload(signedPayload);
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception e) {
            // 파싱 실패도 이벤트 저장
            IapWebhookEvent e0 = new IapWebhookEvent(DeviceType.IOS, "UNKNOWN", "ASN_V2", "FAILED", payload);
            iapWebhookEventRepository.save(e0);
            throw new RuntimeException(e);
        }

        String notificationUUID = root.path("notificationUUID").asText(null);
        if (notificationUUID == null) return;

        // 2) 멱등 이벤트 upsert (처음이면 save)
        IapWebhookEvent event = iapWebhookEventRepository
                .findByPlatformAndDedupKey(DeviceType.IOS, notificationUUID)
                .orElseGet(() -> iapWebhookEventRepository.save(
                        new IapWebhookEvent(
                                DeviceType.IOS, notificationUUID,
                                root.path("notificationType").asText("UNKNOWN"),
                                "PENDING", payload
                        )
                ));

        if ("DONE".equals(event.getProcessStatus())) return; // 이미 처리됨

        try {
            // 3) 트랜잭션ID 추출용 JWS 재검증
            String signedTransactionInfo = root.path("data").path("signedTransactionInfo").asText(null);
            if (signedTransactionInfo == null) {
                event.updateProcessStatus("DONE");
                iapWebhookEventRepository.save(event);
                return;
            }
            String txPayload = appleJwsVerifier.verifyAndGetPayload(signedTransactionInfo);
            JsonNode tx = objectMapper.readTree(txPayload);
            String latestTransactionId = tx.path("transactionId").asText(null);
            if (latestTransactionId == null) {
                event.updateProcessStatus("DONE");
                iapWebhookEventRepository.save(event);
                return;
            }

            // 4) 우리 서버 검증 재사용
            VerifyRequest verifyRequest = new VerifyRequest("ios", latestTransactionId, null, null);
            iapService.verify(verifyRequest);

            // 5) 완료 마킹
            event.updateProcessStatus("DONE");
            iapWebhookEventRepository.save(event);

        } catch (Exception ex) {
            event.updateProcessStatus("FAILED");
            iapWebhookEventRepository.save(event);
            throw new RuntimeException(ex);
        }
    }
}

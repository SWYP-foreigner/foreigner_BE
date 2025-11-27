package core.domain.payment.entity;

import core.global.enums.DeviceType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "iap_webhook_event",
        uniqueConstraints = @UniqueConstraint(name="ux_iap_webhook_dedup", columnNames={"platform","dedup_key"})
)
@Getter
@NoArgsConstructor
public class IapWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 16)
    private DeviceType platform;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType; // ASN/RTDN 타입명 등

    @Column(name = "dedup_key", nullable = false, length = 256)
    private String dedupKey;  // notificationUUID / messageId 등

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "process_status", nullable = false, length = 16)
    private String processStatus = "PENDING"; // PENDING|DONE|FAILED

    @Lob
    @Column(name = "raw_json")
    private String rawJson;

    // 사용 중인 생성자 시그니처 지원
    public IapWebhookEvent(DeviceType platform, String dedupKey,
                           String eventType, String processStatus, String rawJson) {
        this.platform = platform;
        this.dedupKey = dedupKey;
        this.eventType = eventType;
        this.processStatus = processStatus;
        this.rawJson = rawJson;
        this.receivedAt = Instant.now();
    }

    public void updateProcessStatus(String processStatus) {
        this.processStatus = processStatus;
        if ("DONE".equals(processStatus)) {
            this.processedAt = Instant.now();
        }
    }
}
package core.domain.payment.repository;

import core.domain.payment.entity.IapWebhookEvent;
import core.global.enums.DeviceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IapWebhookEventRepository extends JpaRepository<IapWebhookEvent, Long> {
    Optional<IapWebhookEvent> findByPlatformAndDedupKey(DeviceType platform, String dedupKey);
}
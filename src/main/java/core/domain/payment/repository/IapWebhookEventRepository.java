package core.domain.payment.repository;

import core.domain.payment.entity.IapWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IapWebhookEventRepository extends JpaRepository<IapWebhookEvent, Long> {
    Optional<IapWebhookEvent> findByPlatformAndDedupKey(String platform, String dedupKey);
}
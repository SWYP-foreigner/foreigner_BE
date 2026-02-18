package core.domain.payment.repository;

import core.domain.payment.entity.IapEntitlement;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface IapEntitlementRepository extends JpaRepository<IapEntitlement, Long> {
    Optional<IapEntitlement> findTopByUserIdOrderByUpdatedAtDesc(Long userId);
    void deleteAllByUserId(Long userId);
}
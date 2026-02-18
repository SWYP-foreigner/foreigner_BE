package core.domain.payment.repository;

import core.domain.payment.entity.IapPurchase;
import core.global.enums.DeviceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IapPurchaseRepository extends JpaRepository<IapPurchase, Long> {
    Optional<IapPurchase> findByPlatformAndStoreTxId(DeviceType platform, String storeTxId);
    void deleteAllByUserId(Long userId);
}
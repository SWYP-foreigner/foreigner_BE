package core.domain.payment.repository;

import core.domain.payment.entity.IapProduct;
import core.global.enums.DeviceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IapProductRepository extends JpaRepository<IapProduct, Long> {
    Optional<IapProduct> findByPlatformAndStoreProductId(DeviceType platform, String storeProductId);
}
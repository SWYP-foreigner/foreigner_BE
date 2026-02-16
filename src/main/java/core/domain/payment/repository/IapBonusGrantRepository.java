package core.domain.payment.repository;

import core.domain.payment.entity.IapBonusGrant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IapBonusGrantRepository extends JpaRepository<IapBonusGrant, Long> {
    Optional<IapBonusGrant> findByUserIdAndBonusCode(Long userId, String bonusCode);
}
package core.domain.payment.repository;

import core.domain.payment.entity.UserItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserItemRepository extends JpaRepository<UserItem, Long> {
    Optional<UserItem> findByUserIdAndItemCode(Long userId, String itemCode);

    @Modifying
    @Query("""
              UPDATE UserItem ui
                 SET ui.quantity = ui.quantity - :delta,
                     ui.updatedAt = CURRENT_TIMESTAMP
               WHERE ui.userId = :userId
                 AND ui.itemCode = :itemCode
                 AND ui.quantity >= :delta
            """)
    int decrementIfEnough(@Param("userId") Long userId,
                          @Param("itemCode") String itemCode,
                          @Param("delta") int delta);
    void deleteAllByUserId(Long userId);
}

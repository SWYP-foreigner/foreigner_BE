package core.domain.payment.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;

import java.time.Instant;

@Entity
@Table(name = "iap_bonus_grant",
        uniqueConstraints = @UniqueConstraint(name = "ux_iap_bonus_user_bonus", columnNames = {"user_id", "bonus_code"})
)
@Getter
@NoArgsConstructor
public class IapBonusGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "iap_bonus_grant_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "bonus_code", nullable = false, length = 64)
    private String bonusCode;   // 'welcome_frame'

    @Column(name = "dedup_key", length = 256)
    private String dedupKey;    // originalTxId 등

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_purchase_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private IapPurchase sourcePurchase;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public IapBonusGrant(IapPurchase purchase, String ment) {
        this.userId = purchase.getUserId();
        this.bonusCode = ment;
        this.dedupKey = purchase.getOriginalTxId() != null ? purchase.getOriginalTxId() : purchase.getStoreTxId();
        this.sourcePurchase = purchase;
    }
}
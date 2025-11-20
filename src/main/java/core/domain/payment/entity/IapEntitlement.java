package core.domain.payment.entity;

import core.global.enums.DeviceType;
import core.global.enums.payment.EntitlementStatus;
import core.global.enums.payment.EntitlementStatus.*;
import core.global.enums.payment.PurchaseStatus;
import jakarta.persistence.*;
import lombok.Getter;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.Instant;


@Getter
@Entity
@Table(name = "iap_entitlement",
        indexes = @Index(name = "ix_iap_entitlement_user", columnList = "user_id")
)
public class IapEntitlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_purchase_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private IapPurchase sourcePurchase;

    @Column(name = "feature", nullable = false, length = 64)
    private String feature; // ex) pro

    @Column(name = "tier", nullable = false, length = 64)
    private String tier;    // ex) basic/premium

    @Column(name = "active", nullable = false)
    private boolean active;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private EntitlementStatus status;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private DeviceType source;  // ios | android

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public IapEntitlement(Long userId, boolean active, EntitlementStatus entitlementStatus) {
        this.userId = userId;
        this.active = active;
        this.status = entitlementStatus;
    }

    public IapEntitlement() {

    }

    private IapEntitlement(IapPurchase p) {
        this.userId = p.getUserId();
        this.sourcePurchase = p;
        this.feature = p.getProduct() != null ? p.getProduct().getFeature() : "pro";
        this.tier = p.getProduct() != null ? p.getProduct().getTier() : "plus";
        this.active = (p.getStatus() == PurchaseStatus.ACTIVE || p.getStatus() == PurchaseStatus.IN_GRACE);
        this.status = switch (p.getStatus()) {
            case ACTIVE, IN_GRACE -> EntitlementStatus.ACTIVE;
            case ON_HOLD, PAUSED -> EntitlementStatus.ON_HOLD;
            case REFUNDED -> EntitlementStatus.REFUNDED;
            default -> EntitlementStatus.EXPIRED;
        };
        this.expiresAt = p.getExpiresAt();
        this.source = p.getPlatform();
        this.updatedAt = Instant.now();
    }

    public static IapEntitlement fromPurchase(IapPurchase p) {
        return new IapEntitlement(p);
    }
}

package core.domain.payment.entity;

import core.global.enums.DeviceType;
import core.global.enums.payment.PaymentProductType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.Instant;

@Entity
@Table(name = "iap_product",
        uniqueConstraints = @UniqueConstraint(name = "ux_iap_product_store", columnNames = {"platform", "store_product_id"})
)
@Getter
@NoArgsConstructor
public class IapProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 16)
    private DeviceType platform;

    @Column(name = "store_product_id", nullable = false, length = 128)
    private String storeProductId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private PaymentProductType type;

    @Column(name = "base_plan", length = 64)
    private String basePlan;

    @Column(name = "offer_id", length = 64)
    private String offerId;

    @Column(name = "feature", nullable = false, length = 64)
    private String feature;   // ex) pro

    @Column(name = "tier", nullable = false, length = 64)
    private String tier;      // ex) basic/premium

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

}
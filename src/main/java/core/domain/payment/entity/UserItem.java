package core.domain.payment.entity;

import jakarta.persistence.*;
import lombok.Getter;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.Instant;

@Getter
@Entity
@Table(name = "user_item",
        uniqueConstraints = @UniqueConstraint(name = "ux_user_item", columnNames = {"user_id", "item_code"})
)
public class UserItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "item_code", nullable = false, length = 64)
    private String itemCode; // 'boost', 'frame', ...

    @Column(name = "item_code")
    private Integer quantity = 0;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UserItem(Long userId, String itemCode, Integer quantity) {
        this.userId = userId;
        this.itemCode = itemCode;
        this.quantity = quantity;
    }

    public void updateQuantity(int quantity) {
        this.quantity = quantity;
    }

    public void minusQuantity(int quantity) {
        this.quantity -= quantity;
    }
}

package core.domain.user.entity;

import core.global.config.StringCryptoConverter;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "admin_otp")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminOtp {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Convert(converter = StringCryptoConverter.class)
    @Column(name = "secret_key", nullable = false)
    private String secretKey;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    public AdminOtp(User user, String secretKey) {
        this.user = user;
        this.secretKey = secretKey;
        this.createdAt = Instant.now();
    }

    public void useToken() {
        this.lastUsedAt = Instant.now();
    }
}

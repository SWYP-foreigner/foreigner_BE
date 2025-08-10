package core.domain.notification;

import com.foreigner.core.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "Notification")
@Getter
@NoArgsConstructor
public class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "message", nullable = false)
    private String message; // 원문 DEFAULT '메세지'는 제거

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "is_read", nullable = false)
    private Boolean read;

    @Column(name = "reference_id", nullable = false)
    private Long referenceId; // 무엇을 참조하는지 미상 (확실하지 않음)

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false)
    private NotificationType notificationType; // 추측
}


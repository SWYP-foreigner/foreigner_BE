package core.domain.notification.entity;

import core.domain.user.entity.User;
import core.global.enums.NotificationType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "notification") // 테이블 이름을 소문자와 스네이크 케이스로 변경하는 것을 권장합니다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 엔티티는 기본 생성자가 필요합니다.
@AllArgsConstructor(access = AccessLevel.PRIVATE) // Builder를 통한 생성을 강제하기 위해 private으로 설정
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user; // 알림을 받는 사람

    @Column(name = "message", nullable = false, length = 255)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "reference_id") // 채팅방 ID, 게시글 ID 등
    private Long referenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false)
    private NotificationType notificationType;

    @Builder
    public Notification(User user, String message, Long referenceId, NotificationType notificationType) {
        this.user = user;
        this.message = message;
        this.referenceId = referenceId;
        this.notificationType = notificationType;
        this.createdAt = Instant.now(); // ⭐️ 생성 시 자동으로 현재 시간 저장
        this.read = false; // ⭐️ 생성 시 기본값은 '안 읽음'
    }

    /**
     * 알림을 '읽음' 상태로 변경하는 편의 메소드
     */
    public void markAsRead() {
        this.read = true;
    }
}
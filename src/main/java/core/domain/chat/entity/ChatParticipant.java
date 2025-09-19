package core.domain.chat.entity;

import core.global.enums.ChatParticipantStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "chat_participant",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"chatroom_id", "user_id"})
        }
)
@Getter
@NoArgsConstructor
public class ChatParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ChatParticipant_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chatroom_id", nullable = false)
    private ChatRoom chatRoom;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "joined_at", updatable = false)
    private Instant joinedAt;

    @Column(name = "last_read_message_id")
    private String lastReadMessageId;

    @Enumerated(EnumType.STRING)
    private ChatParticipantStatus status;

    @Column(name = "last_left_at")
    private Instant lastLeftAt;

    @Column(name = "translate_enabled", nullable = false)
    private boolean translateEnabled = false;

    public ChatParticipant(ChatRoom chatRoom, Long userId) {
        this.chatRoom = chatRoom;
        this.userId = userId;
        this.status = ChatParticipantStatus.ACTIVE;
        this.joinedAt = Instant.now();
        this.lastReadMessageId = null;
    }

    public void updateLastReadMessageId(String messageId) {
        this.lastReadMessageId = messageId;
    }

    public void toggleTranslation(boolean enabled) {
        this.translateEnabled = enabled;
    }

    public void leave() {
        this.status = ChatParticipantStatus.LEFT;
        this.lastLeftAt = Instant.now();
    }

    public void reJoin() {
        this.status = ChatParticipantStatus.ACTIVE;
        this.lastLeftAt = null;
    }
}
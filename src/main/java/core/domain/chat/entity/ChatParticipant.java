package core.domain.chat.entity;

import core.domain.user.entity.User;
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
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ChatParticipant_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chatroom_id", nullable = false)
    private ChatRoom chatRoom;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "joined_at", updatable = false)
    private Instant joinedAt;

    @Column(name = "last_read_message_id")
    private Long lastReadMessageId;

    @Enumerated(EnumType.STRING)
    private ChatParticipantStatus status;

    @Column(name = "last_left_at")
    private Instant lastLeftAt;

    @Column(name = "translate_enabled", nullable = false)
    private boolean translateEnabled = false;

    public void toggleTranslation(boolean enabled) {
        this.translateEnabled = enabled;
    }

    public ChatParticipant(ChatRoom chatRoom, User user) {
        this.chatRoom = chatRoom;
        this.user = user;
        this.status = ChatParticipantStatus.ACTIVE;
        this.joinedAt = Instant.now();
    }
    public void delete() {
        this.status = ChatParticipantStatus.LEFT;
    }


    public void leave() {
        this.status = ChatParticipantStatus.LEFT;
        this.lastLeftAt = Instant.now();
    }

    public void reJoin() {
        this.status = ChatParticipantStatus.ACTIVE;
        this.lastLeftAt = null;
    }
    public void setLastReadMessageId(Long messageId) {
        this.lastReadMessageId = messageId;
    }
}


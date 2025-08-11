package core.domain.chat.entity;

import core.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "ChatParticipant")
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

    @Column(name = "is_blocked")
    private Boolean blocked;

    @Column(name = "is_deleted")
    private Boolean deleted;
}


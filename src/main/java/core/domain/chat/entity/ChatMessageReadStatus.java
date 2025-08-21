package core.domain.chat.entity;


import core.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "chat_message_read_status")
@Getter
@Setter
@NoArgsConstructor
public class ChatMessageReadStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_message_id", nullable = false)
    private ChatMessage chatMessage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reader_id", nullable = false)
    private User reader;

    @Column(name = "read_at", nullable = false)
    private Instant readAt;

    public ChatMessageReadStatus(ChatMessage chatMessage, User reader, Instant readAt) {
        this.chatMessage = chatMessage;
        this.reader = reader;
        this.readAt = readAt;
    }
}
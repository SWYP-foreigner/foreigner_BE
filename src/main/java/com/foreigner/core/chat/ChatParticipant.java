package com.foreigner.core.chat;

import com.foreigner.core.user.User;
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

    @Column(name = "joined_at")
    private Instant joinedAt;

    // 원문은 TIMESTAMP 타입인데 이름은 last_read_message_id → 타입 불일치 (확실하지 않음)
    // 실제로는 BIGINT(message_id) 추천. 여기서는 명시적으로 Instant로 보관 시맨틱이 안 맞으므로 Long으로 저장.
    @Column(name = "last_read_message_id")
    private Long lastReadMessageId; // 스키마 수정 권고

    @Column(name = "is_blocked")
    private Boolean blocked;

    @Column(name = "is_deleted")
    private Boolean deleted;
}


package com.foreigner.core.chat;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "chat_room")
@Getter
@NoArgsConstructor
public class ChatRoom {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chatroom_id")
    private Long id;

    @Column(name = "is_group", nullable = false)
    private Boolean group; // 원문 DEFAULT에 한국어 주석 → 제거

    @Column(name = "created_at")
    private Instant createdAt; // 원문: DEFAULT 만든시간 NULL → 보정
}

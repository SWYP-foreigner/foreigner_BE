package core.domain.chat.dto;

import core.domain.chat.entity.ChatMessage;

import java.time.Instant;

public record ChatMessageResponse(
        Long messageId,
        Long roomId,
        Long senderId,
        String content,
        Instant sentAt
) {
    public static ChatMessageResponse fromEntity(ChatMessage entity) {
        return new ChatMessageResponse(
                entity.getId(),
                entity.getChatRoom().getId(),
                entity.getSender().getId(),
                entity.getContent(),
                entity.getSentAt()
        );
    }
}
package core.domain.chat.dto;

import core.domain.chat.entity.ChatMessage;

import java.time.Instant;
public record ChatMessageResponse(
        Long id,
        Long roomId,
        Long senderId,
        String content,
        Instant sentAt,
        String originalContent
) {
    public static ChatMessageResponse fromEntity(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getChatRoom().getId(),
                message.getSender().getId(),
                message.getContent(),
                message.getSentAt(),
                null
        );
    }
}
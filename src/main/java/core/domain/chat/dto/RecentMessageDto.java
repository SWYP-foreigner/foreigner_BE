package core.domain.chat.dto;

import core.domain.chat.entity.ChatMessage;

import java.time.Instant;

public record RecentMessageDto(
        Long messageId,
        Long chatRoomId,
        String content,
        Instant sentAt
) {
    public static RecentMessageDto from(ChatMessage message) {
        String truncatedContent = message.getContent().length() > 50 ?
                message.getContent().substring(0, 50) + "..." : message.getContent();
        return new RecentMessageDto(message.getId(), message.getChatRoom().getId(), truncatedContent, message.getSentAt());
    }
}
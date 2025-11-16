package core.domain.chat.dto;

import core.domain.chat.entity.ChatMessage;

import java.time.Instant;

public record ChatMessageSearchResultDto(
        Long messageId,
        Long chatRoomId,
        String roomName,
        Long senderId,
        String senderName,
        String senderEmail,
        String content,
        Instant sentAt
) {
    public static ChatMessageSearchResultDto from(ChatMessage message) {
        String truncatedContent = message.getContent().length() > 50 ?
                message.getContent().substring(0, 50) + "..." : message.getContent();
        return new ChatMessageSearchResultDto(
                message.getId(),
                message.getChatRoom().getId(),
                message.getChatRoom().getRoomName(),
                message.getSender().getId(),
                message.getSender().getFirstName()+" "+message.getSender().getLastName(),
                message.getSender().getEmail(),
                truncatedContent,
                message.getSentAt()
        );
    }
}

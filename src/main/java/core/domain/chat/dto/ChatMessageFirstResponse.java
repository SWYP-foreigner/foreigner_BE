package core.domain.chat.dto;

import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatRoom;
import core.domain.user.entity.User;

import java.time.Instant;

public record ChatMessageFirstResponse(
        Long id,
        Long roomId,
        Long senderId,
        String senderFirstName,
        String senderLastName,
        String senderImageUrl,
        String content,
        Instant sentAt
) {
    public static ChatMessageFirstResponse fromEntity(ChatMessage message, ChatRoom room, String senderImageUrl) {
        User sender = message.getSender();

        return new ChatMessageFirstResponse(
                message.getId(),
                message.getChatRoom().getId(),
                sender.getId(),
                sender.getFirstName(),
                sender.getLastName(),
                senderImageUrl,
                message.getContent(),
                message.getSentAt()
        );
    }
}
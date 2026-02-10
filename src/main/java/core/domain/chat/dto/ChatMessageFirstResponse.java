package core.domain.chat.dto;

import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatRoom;
import core.domain.user.entity.User;
import core.global.enums.chat.MessageType;

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

    public static ChatMessageFirstResponse fromEntityWithContent(
            ChatMessage message,
            ChatRoom room,
            String senderImageUrl,
            String finalContent,
            MessageType finalType
    ) {
        User sender = message.getSender();

        return new ChatMessageFirstResponse(
                message.getId(),
                room.getId(),
                sender.getId(),
                sender.getFirstName(),
                sender.getLastName(),
                senderImageUrl,
                finalContent,
                message.getSentAt()
        );
    }
}
package core.domain.chat.dto;

import core.global.enums.MessageType;

public record SendMessageRequest(
        Long roomId,
        Long senderId,
        String content,
        MessageType type
) {
    public SendMessageRequest {
        if (type == null) {
            type = MessageType.TEXT;
        }
    }
}
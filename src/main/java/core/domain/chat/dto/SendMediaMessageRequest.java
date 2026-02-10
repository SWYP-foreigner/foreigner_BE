package core.domain.chat.dto;

import core.global.enums.chat.MessageType;

public record SendMediaMessageRequest(
        Long roomId,
        Long senderId,
        MessageType messageType,
        String mediaKey,
        String thumbnailKey
) {}
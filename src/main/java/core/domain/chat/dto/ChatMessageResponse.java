package core.domain.chat.dto;

import core.global.enums.MessageType;

import java.time.Instant;
import java.time.LocalDateTime;

public record ChatMessageResponse(
        Long id,
        Long roomId,
        Long senderId,
        String originContent,
        String targetContent,
        Instant sentAt,

        // [Sender Info]
        String senderFirstName,
        String senderLastName,
        String senderImageUrl,

        MessageType messageType,

        // [New Fields for Media]
        String mediaUrl,        // 실제 이미지/비디오 URL
        String thumbnailUrl     // 비디오 썸네일 URL
) {
}
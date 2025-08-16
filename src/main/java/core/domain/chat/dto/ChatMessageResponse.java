package core.domain.chat.dto;

import java.time.Instant;

public record ChatMessageResponse(
        Long messageId,
        Long roomId,
        Long senderId,
        String content,
        Instant sentAt
) {}
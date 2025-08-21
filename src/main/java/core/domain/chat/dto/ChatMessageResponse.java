package core.domain.chat.dto;

import java.time.Instant;

public record ChatMessageResponse(
        String id,
        Long senderId,
        String senderName,
        String content,
        Instant createdAt
) {}
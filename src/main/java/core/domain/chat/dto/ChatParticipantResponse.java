package core.domain.chat.dto;

import java.time.Instant;

public record ChatParticipantResponse(
        Long id,
        Long userId,
        String userName,
        Instant joinedAt,
        Long lastReadMessageId,
        Boolean blocked,
        Boolean deleted
) {}
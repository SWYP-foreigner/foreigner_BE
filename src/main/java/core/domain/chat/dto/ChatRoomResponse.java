package core.domain.chat.dto;

import java.time.Instant;
import java.util.List;

public record ChatRoomResponse(
        Long id,
        Boolean isGroup,
        Instant createdAt,
        List<ChatParticipantResponse> participants
) {}

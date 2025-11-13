package core.domain.chat.dto;

import java.time.Instant;

public record ChatRoomSummaryResponse(
        Long roomId,
        String roomName,
        String lastMessageContent,
        Instant lastMessageTime,
        String roomImageUrl,
        int unreadCount,
        int participantCount
) {}
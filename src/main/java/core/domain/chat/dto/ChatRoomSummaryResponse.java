package core.domain.chat.dto;

import java.time.LocalDateTime;

// ChatRoomSummaryResponse (예시 DTO)
public record ChatRoomSummaryResponse(
        Long roomId,
        String lastMessage,
        LocalDateTime lastMessageTime,
        int unreadCount
) {}

package core.domain.chat.dto;

import core.domain.chat.entity.ChatRoom;

import java.time.Instant;

public record ChatRoomListResponse(
        Long chatRoomId,
        String roomName,
        boolean isGroup,
        long participantCount,
        Instant createdAt
) {
    public static ChatRoomListResponse from(ChatRoom chatRoom, long participantCount) {
        return new ChatRoomListResponse(
                chatRoom.getId(),
                chatRoom.getRoomName(),
                chatRoom.getGroup(),
                participantCount,
                chatRoom.getCreatedAt()
        );
    }
}

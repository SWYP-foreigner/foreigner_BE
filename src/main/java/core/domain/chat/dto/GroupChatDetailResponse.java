package core.domain.chat.dto;

import java.util.List;
import core.domain.chat.entity.ChatRoom;

public record GroupChatDetailResponse(
        Long chatRoomId,
        String roomName,
        String description,
        Long owner_id,
        String ownerFirstName,
        String ownerLastName,
        String roomImageUrl,
        int participantCount,
        List<String> participants_image_url
) {
    public static GroupChatDetailResponse from(
            ChatRoom chatRoom,
            String roomImageUrl,
            int participantCount,
            List<String> participants_image_url
    ) {
        return new GroupChatDetailResponse(
                chatRoom.getId(),
                chatRoom.getRoomName(),
                chatRoom.getDescription(),
                chatRoom.getOwner().getId(),
                chatRoom.getOwner().getFirstName(),
                chatRoom.getOwner().getLastName(),
                roomImageUrl,
                participantCount,
                participants_image_url
        );
    }
}
package core.domain.chat.dto;

import core.domain.chat.entity.ChatRoom;
import java.util.List;

// 필드명의 오타(onwer_image_Url)와 네이밍 컨벤션(owner_id)을 수정했습니다.
public record GroupChatDetailResponse(
        Long chatRoomId,
        String roomName,
        String description,
        Long ownerId, // owner_id -> ownerId
        String ownerFirstName,
        String ownerLastName,
        String roomImageUrl,
        int participantCount,
        List<String> participantsImageUrls, // participants_image_url -> participantsImageUrls
        String ownerImageUrl // onwer_image_Url -> ownerImageUrl
) {
    public static GroupChatDetailResponse from(
            ChatRoom chatRoom,
            String roomImageUrl,
            int participantCount,
            List<String> participantsImageUrls,
            String ownerImageUrl // 5번째 파라미터로 ownerImageUrl 추가
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
                participantsImageUrls,
                ownerImageUrl // 생성자에 ownerImageUrl 전달
        );
    }
}
package core.domain.chat.dto;

import core.domain.chat.entity.ChatRoom;
import io.swagger.v3.oas.annotations.media.Schema;

public record ChatRecommendRoomResponse(
        @Schema(description = "채팅방 ID", example = "10")
        Long roomId,

        @Schema(description = "채팅방 이름", example = "스터디 2024년 2차 그룹")
        String roomName,

        @Schema(description = "채팅방 대표 이미지 URL", example = "https://cdn.example.com/chat/room_10.jpg")
        String roomImageUrl
) {
    public static ChatRecommendRoomResponse of(ChatRoom room, String imageUrl) {
        return new ChatRecommendRoomResponse(
                room.getId(),
                room.getRoomName(),
                imageUrl
        );
    }
}
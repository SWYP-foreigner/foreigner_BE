package core.domain.chat.dto;

import core.domain.chat.entity.ChatRoom;
import core.domain.chat.entity.ChatParticipant;
import core.domain.user.entity.User;
import core.global.enums.ImageType;
import core.global.image.entity.Image;
import core.global.image.repository.ImageRepository;

import java.time.Instant;

public record ChatRoomSummaryResponse(
        Long roomId,
        String roomName,
        String lastMessageContent,
        Instant lastMessageTime,
        String roomImageUrl,
        int unreadCount,
        int participantCount
) {

    public static ChatRoomSummaryResponse from(
            ChatRoom room,
            Long userId,
            String lastMessageContent,
            Instant lastMessageTime,
            int unreadCount,
            ImageRepository imageRepository
    ) {
        String roomName;
        String roomImageUrl;

        if (!room.getGroup()) { // 1:1 채팅방
            User opponent = room.getParticipants().stream()
                    .map(ChatParticipant::getUser)
                    .filter(u -> !u.getId().equals(userId))
                    .findFirst()
                    .orElse(null);

            if (opponent == null) {
                // 상대방 participant가 없는 경우
                roomName = "알 수 없음";
                roomImageUrl = null;
            } else {
                // participant 존재 → 상태와 상관없이 이름 그대로
                roomName = opponent.getLastName() != null ? opponent.getLastName() : opponent.getFirstName();
                roomImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, opponent.getId())
                        .map(Image::getUrl)
                        .orElse(null);
            }
        } else { // 그룹 채팅방
            roomName = room.getRoomName();
            roomImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.CHAT_ROOM, room.getId())
                    .map(Image::getUrl)
                    .orElse(null);
        }

        return new ChatRoomSummaryResponse(
                room.getId(),
                roomName,
                lastMessageContent,
                lastMessageTime,
                roomImageUrl,
                unreadCount,
                room.getParticipants().size()
        );
    }
}

package core.domain.chat.dto;

import core.domain.chat.entity.ChatRoom;
import core.domain.user.entity.User;
import core.global.enums.ImageType;
import core.global.image.repository.ImageRepository;

import java.time.LocalDateTime;
import core.global.image.entity.Image;
public record ChatRoomSummaryResponse(
        // 1. 채팅방 아이디
        Long roomId,
        // 2. 채팅방 이름 (1:1: 상대방 이름, 그룹: 방 이름)
        String roomName,
        // 3. 마지막 채팅 내용
        String lastMessageContent,
        // 4. 마지막 채팅 시간
        LocalDateTime lastMessageTime,
        // 5. 채팅방 사진 (1:1: 상대방 프로필 사진, 그룹: 방 사진)
        String roomImageUrl,
        // 6. 안 읽은 메시지
        int unreadCount,
        // 7. 채팅방 인원수
        int participantCount
) {

    public static ChatRoomSummaryResponse from(
            ChatRoom room,
            Long userId,
            String lastMessageContent,
            LocalDateTime lastMessageTime,
            int unreadCount,
            ImageRepository imageRepository
    ) {
        String roomName;
        String roomImageUrl;


        if (!room.getGroup()) {
            User opponent = room.getParticipants().stream()
                    .map(p -> p.getUser())
                    .filter(u -> !u.getId().equals(userId)) // ✅ userId 사용
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("1:1 채팅방에 상대방이 없습니다."));

            roomName = opponent.getLastName();
            roomImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, opponent.getId())
                    .map(Image::getUrl)
                    .orElse(null);
        }  else {
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

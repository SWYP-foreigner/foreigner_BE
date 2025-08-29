package core.domain.chat.dto;

import core.domain.chat.entity.ChatRoom;
import core.domain.user.entity.User;
import core.global.enums.ImageType;
import core.global.image.repository.ImageRepository;

import java.time.LocalDateTime;
import core.global.image.entity.Image;
import java.time.format.DateTimeFormatter;

// ChatRoomSummaryResponse 수정
public record ChatRoomSummaryResponse(
        Long roomId,
        String roomName,
        String lastMessageContent,
        String lastMessageTime, // ✅ LocalDateTime → String
        String roomImageUrl,
        int unreadCount,
        int participantCount
) {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

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
                    .filter(u -> !u.getId().equals(userId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("1:1 채팅방에 상대방이 없습니다."));

            roomName = opponent.getLastName();
            roomImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, opponent.getId())
                    .map(Image::getUrl)
                    .orElse(null);
        } else {
            roomName = room.getRoomName();
            roomImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.CHAT_ROOM, room.getId())
                    .map(Image::getUrl)
                    .orElse(null);
        }

        // LocalDateTime → HH:mm 포맷
        String lastMessageTimeStr = lastMessageTime != null ? lastMessageTime.format(TIME_FORMATTER) : null;

        return new ChatRoomSummaryResponse(
                room.getId(),
                roomName,
                lastMessageContent,
                lastMessageTimeStr,
                roomImageUrl,
                unreadCount,
                room.getParticipants().size()
        );
    }
}

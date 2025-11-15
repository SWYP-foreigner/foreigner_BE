package core.domain.user.dto;

import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;

public record ChatRoomInfoDto(
        Long chatRoomId,
        String roomName,
        boolean isGroup,
        long participantCount
) {
    public static ChatRoomInfoDto from(ChatParticipant participant) {
        ChatRoom chatRoom = participant.getChatRoom();
        return new ChatRoomInfoDto(
                chatRoom.getId(),
                chatRoom.getRoomName(),
                chatRoom.getGroup(),
                chatRoom.getParticipants().size()
        );
    }
}

package core.domain.chat.dto;

public record ChatRoomParticipantsResponse(
        Long userId,
        String firstName, // 오타 수정: finstName -> firstName
        String lastName,
        String userImageUrl,
        boolean isHost
) {
}
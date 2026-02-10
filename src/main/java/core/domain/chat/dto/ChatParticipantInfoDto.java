package core.domain.chat.dto;

import core.domain.chat.entity.ChatParticipant;
import core.global.enums.ChatParticipantStatus;

import java.time.Instant;

public record ChatParticipantInfoDto(
        Long participantId,
        Long userId,
        String userName,
        Instant joinedAt,
        ChatParticipantStatus status
) {
    public static ChatParticipantInfoDto from(ChatParticipant participant) {
        return new ChatParticipantInfoDto(
                participant.getId(),
                participant.getUser().getId(),
                participant.getUser().getFirstName()+" "+participant.getUser().getLastName(),
                participant.getJoinedAt(),
                participant.getStatus()
        );
    }
}

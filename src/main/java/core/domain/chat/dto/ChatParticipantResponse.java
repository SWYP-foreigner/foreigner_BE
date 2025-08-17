package core.domain.chat.dto;

import core.domain.chat.entity.ChatParticipant;

import java.time.Instant;

public record ChatParticipantResponse(
        Long id,
        Long userId,
        String userName,
        Instant joinedAt,
        Long lastReadMessageId,
        boolean blocked,
        boolean deleted
) {
    public static ChatParticipantResponse from(ChatParticipant participant) {
        return new ChatParticipantResponse(
                participant.getId(),
                participant.getUser().getId(),
                participant.getUser().getName(),
                participant.getJoinedAt(),
                participant.getLastReadMessageId(),
                participant.getBlocked(),
                participant.getDeleted()
        );
    }
}
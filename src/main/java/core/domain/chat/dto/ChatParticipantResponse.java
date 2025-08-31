package core.domain.chat.dto;

import core.domain.chat.entity.ChatParticipant;
import core.global.enums.ChatParticipantStatus;

import java.time.Instant;

public record ChatParticipantResponse(
        Long userId,
        String firstName,
        String lastName,
        String userImageUrl,
        boolean isHost
) {
}
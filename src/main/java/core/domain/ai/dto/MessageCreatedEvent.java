package core.domain.ai.dto;

import core.domain.chat.dto.ChatMessageResponse;

import java.util.List;

public record MessageCreatedEvent(
        ChatMessageResponse messageResponse,
        List<Long> allRecipientIds
) {}
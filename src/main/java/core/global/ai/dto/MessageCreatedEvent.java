package core.global.ai.dto;

import core.domain.chat.dto.ChatMessageResponse;

import java.util.List;

public record MessageCreatedEvent(
        ChatMessageResponse messageResponse,
        List<Long> allRecipientIds // 참고용으로 수신자 목록만 전달
) {}
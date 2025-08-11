package core.domain.chat.dto;

public record SendMessageRequest(Long senderId, String content) {}
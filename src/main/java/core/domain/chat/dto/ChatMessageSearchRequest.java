package core.domain.chat.dto;

public record ChatMessageSearchRequest(
        String keyword,
        String senderEmail,
        String senderName
) {}

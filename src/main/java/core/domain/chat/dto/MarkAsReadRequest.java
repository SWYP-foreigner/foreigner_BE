package core.domain.chat.dto;


public record MarkAsReadRequest(
        Long roomId,
        Long lastReadMessageId
) {}
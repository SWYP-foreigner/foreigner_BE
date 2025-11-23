package core.domain.chat.dto;
public record MessageDeletedEvent(
        Long roomId,
        Long messageId
) {}
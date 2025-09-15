package core.domain.chat.dto;

import java.time.Instant;

public record ChatMessageResponse(
        Long id,
        Long roomId,
        Long senderId,
        String originContent,
        String targetContent,
        Instant sentAt,
        String senderFirstName,
        String senderLastName,
        String senderImageUrl
) {

}
package core.domain.chat.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record SendMessageRequest(
        Long roomId,
        Long senderId,
        String content,
        String targetLanguage,
        boolean translate
) {
    public SendMessageRequest(Long roomId, Long senderId, String content, boolean translate) {
        this(roomId, senderId, content, null, translate);
    }
}
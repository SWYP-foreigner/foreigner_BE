<<<<<<<< HEAD:src/main/java/core/domain/ai/dto/MessageCreatedEvent.java
package core.domain.ai.dto;
========
package core.domain.aiuser.dto;
>>>>>>>> test:src/main/java/core/domain/aiuser/dto/MessageCreatedEvent.java

import core.domain.chat.dto.ChatMessageResponse;

import java.util.List;

public record MessageCreatedEvent(
        ChatMessageResponse messageResponse,
        List<Long> allRecipientIds
) {}
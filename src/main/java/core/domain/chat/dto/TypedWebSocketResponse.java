package core.domain.chat.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

public record TypedWebSocketResponse<T>(
        String type,

        @JsonUnwrapped
        T data
) {}
package core.global.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public record ApiErrorResponse(
        String error,
        String error_code,
        String message,
        Object detail,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime timestamp
) {
    public static ApiErrorResponse of(String error, String message) {
        return new ApiErrorResponse(error, null, message, null, LocalDateTime.now());
    }

    public static ApiErrorResponse of(String error, String errorCode, String message) {
        return new ApiErrorResponse(error, errorCode, message, null, LocalDateTime.now());
    }

    public static ApiErrorResponse of(String error, String errorCode, String message, Object detail) {
        return new ApiErrorResponse(error, errorCode, message, detail, LocalDateTime.now());
    }
}

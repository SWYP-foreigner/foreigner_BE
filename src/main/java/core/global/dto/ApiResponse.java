package core.global.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public record ApiResponse<T>(
        String message,
        T data,
        String errorCode,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime timestamp
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("success", data, null, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> error(String message, String errorCode) {
        return new ApiResponse<>(message, null, errorCode, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> fail(String message, String errorCode) {
        return new ApiResponse<>(message, null, errorCode, LocalDateTime.now());
    }
}
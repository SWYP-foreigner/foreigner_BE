package core.global.image.dto;

import java.util.Map;

public record PresignedUrlResponse(
        String key,                 // 원본 키 (인코딩하지 않은 경로)
        String putUrl,              // Presigned PUT URL
        String method,              // "PUT"
        Map<String, String> headers// Content-Type 등
) {}
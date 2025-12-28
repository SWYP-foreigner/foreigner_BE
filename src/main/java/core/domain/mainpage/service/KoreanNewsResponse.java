package core.domain.mainpage.service;

import java.time.Instant;

public record KoreanNewsResponse(
        String title,
        String contentPreview,
        KNewsContentType type,
        Instant createdAt
) {
}

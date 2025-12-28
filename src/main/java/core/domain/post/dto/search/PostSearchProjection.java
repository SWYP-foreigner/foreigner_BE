package core.domain.post.dto.search;

import core.global.enums.BoardCategory;

import java.time.Instant;

public record PostSearchProjection(
        Long postId,
        String contentPreview,
        Long authorId,
        String authorName,
        BoardCategory category,
        Instant createdAt,
        boolean isAnonymous,
        long viewCount,
        double rawScore,
        long scoreRounded
) {}
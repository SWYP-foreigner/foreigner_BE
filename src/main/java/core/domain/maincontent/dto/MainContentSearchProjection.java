package core.domain.maincontent.dto;

import core.global.enums.BoardCategory;
import core.global.enums.KNewsContentType;

import java.time.Instant;

public record MainContentSearchProjection(
        Long contentId,
        String title,
        KNewsContentType type,
        Instant createdAt,
        double rawScore,
        long scoreRounded
) {}
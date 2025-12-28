package core.domain.maincontent.dto;

import core.domain.maincontent.entity.KNewsContentType;

public record MainContentTop3Response(
        String title,
        String contentPreview,
        KNewsContentType type,
        Integer ago
) {
}

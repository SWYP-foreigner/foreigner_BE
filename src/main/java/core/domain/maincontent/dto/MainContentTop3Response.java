package core.domain.maincontent.dto;

import core.domain.maincontent.entity.KNewsContentType;

public record MainContentTop3Response(
        Long contentId,
        String title,
        String contentPreview,
        KNewsContentType type,
        Long ago,
        String thumbImageUrl
) {
}

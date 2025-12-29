package core.domain.maincontent.dto;

import core.domain.maincontent.entity.KNewsContentType;

public record MainContentTop9Response(
        Long contentId,
        String title,
        KNewsContentType type,
        Long ago,
        String thumbImageUrl
) {
}

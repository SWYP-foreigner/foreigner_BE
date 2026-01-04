package core.domain.maincontent.dto;

import core.global.enums.KNewsContentType;

public record MainContentTop9Response(
        Long contentId,
        String title,
        KNewsContentType type,
        Long ago,
        String thumbImageUrl
) {
}

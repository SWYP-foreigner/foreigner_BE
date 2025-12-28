package core.domain.maincontent.dto;

import core.domain.maincontent.entity.KNewsContentType;

public record MainContentTop9Response(
        String title,
        KNewsContentType type,
        int ago
        ) {
}

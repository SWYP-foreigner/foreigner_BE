package core.domain.maincontent.dto;

import core.domain.maincontent.entity.MainPageContent;


public record MainPageContentResponse(
        Long contentId,
        String title,
        String htmlContent,
        String originalUrl
) {
    public static MainPageContentResponse from(MainPageContent entity) {
        return new MainPageContentResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getHtmlContent(),
                entity.getOriginalUrl()
        );
    }
}
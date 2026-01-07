package core.domain.admin.dto;

import core.domain.maincontent.entity.MainPageContent;
import core.global.enums.KNewsContentType;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@NoArgsConstructor
public class MainContentListResponse {
    private Long id;
    private String title;
    private KNewsContentType type;
    private String originalUrl;
    private Long viewCount;
    private Instant createdAt;
    private String thumbnailUrl;

    public MainContentListResponse(MainPageContent entity, String thumbnailUrl) {
        this.id = entity.getId();
        this.title = entity.getTitle();
        this.type = entity.getType();
        this.originalUrl = entity.getOriginalUrl();
        this.viewCount = entity.getViewCount();
        this.createdAt = entity.getCreatedAt();
        this.thumbnailUrl = thumbnailUrl;
    }
}

package core.domain.post.dto.crawling;

import core.domain.post.entity.CrawledData;
import core.global.enums.CrawledDataStatus;

import java.time.Instant;
import java.util.List;

public record CrawledDataDto(
        Long id,
        String title,
        String sourceSite,
        String originalUrl,
        Instant crawledAt,
        CrawledDataStatus status,
        List<String> imageUrls // 이미지 목록 확인용
) {
    public static CrawledDataDto from(CrawledData data) {
        return new CrawledDataDto(
                data.getId(),
                data.getTitle(),
                data.getSourceSite(),
                data.getOriginalUrl(),
                data.getCrawledAt(),
                data.getStatus(),
                data.getImageUrls()
        );
    }
}
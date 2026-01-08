package core.domain.admin.dto;

import core.domain.maincontent.entity.MainContent;
import core.global.entity.image.entity.Image;
import core.global.enums.KNewsContentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MainContentDetailDto {

    private Long id;
    private String title;
    private String htmlContent;
    private KNewsContentType type;
    private String originalUrl;
    private Long viewCount;
    private Instant createdAt;
    private Instant updatedAt;
    private String mainThumbnailUrl;
    private String popularThumbnailUrl;
    private List<String> bodyImageUrls;

    public static MainContentDetailDto from(MainContent entity,
                                            String mainThumbnailUrl,
                                            String popularThumbnailUrl,
                                            List<Image> bodyImages) {
        return MainContentDetailDto.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .htmlContent(entity.getHtmlContent())
                .type(entity.getType())
                .originalUrl(entity.getOriginalUrl())
                .viewCount(entity.getViewCount())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .mainThumbnailUrl(mainThumbnailUrl)
                .popularThumbnailUrl(popularThumbnailUrl)
                .bodyImageUrls(bodyImages.stream()
                        .map(Image::getUrl)
                        .collect(Collectors.toList()))
                .build();
    }
}

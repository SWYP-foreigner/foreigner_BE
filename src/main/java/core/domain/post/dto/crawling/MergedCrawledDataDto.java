package core.domain.post.dto.crawling;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class MergedCrawledDataDto {
    private String title;
    private String content;
    private List<String> imageUrls;
}

package core.domain.maincontent.dto;

public record MainContentsSearchResultView(
        MainContentNewsListResponse item,
        double score
) {
}
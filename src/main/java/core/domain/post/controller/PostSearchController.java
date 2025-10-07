package core.domain.post.controller;

import core.domain.post.service.PostSearchService;
import core.global.dto.ApiResponse;
import core.global.metrics.FeatureUsageMetrics;
import core.global.pagination.CursorPageResponse;
import core.domain.post.dto.SearchResultView;
import core.domain.post.service.RecentSearchRedisService;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class PostSearchController {

    private final PostSearchService searchService;
    private final RecentSearchRedisService recentService;
    private final FeatureUsageMetrics featureUsageMetrics;

    @GetMapping("/{boardId}/posts")
    public ResponseEntity<ApiResponse<CursorPageResponse<SearchResultView>>> getPostList(
            @RequestParam String q,
            @PathVariable Long boardId,
            @Parameter(description = "응답의 nextCursor를 그대로 입력(첫 페이지는 비움)", example = "eyJ0IjoiMjAyNS0wOC0yMVQxMjowMDowMFoiLCJpZCI6MTAxfQ")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "페이지 크기(1~50)", example = "20") @RequestParam(defaultValue = "20") int size
    ) {
        featureUsageMetrics.recordCommunityUsage();
        return ResponseEntity.ok(
                core.global.dto.ApiResponse.success(
                        searchService.search(q, boardId, cursor, size)
                ));
    }

    @GetMapping("/{boardId}/suggest")
    public List<String> suggestByBoard(@PathVariable Long boardId,
                                       @RequestParam("q") String q) {
        featureUsageMetrics.recordCommunityUsage();
        return searchService.suggest(q, boardId);
    }

    @GetMapping("/recent")
    public List<String> recent() {
        featureUsageMetrics.recordCommunityUsage();
        return recentService.list();
    }

    @DeleteMapping("/recent")
    public void deleteRecent(@RequestParam String q) {
        featureUsageMetrics.recordCommunityUsage();
        recentService.remove(q);
    }

    @DeleteMapping("/recent/all")
    public void clearRecent() {
        featureUsageMetrics.recordCommunityUsage();
        recentService.clear();
    }
}
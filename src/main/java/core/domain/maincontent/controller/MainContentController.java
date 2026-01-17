package core.domain.maincontent.controller;

import core.domain.maincontent.dto.MainContentNewsListResponse;
import core.domain.maincontent.dto.MainContentNewsResponse;
import core.domain.maincontent.dto.MainContentTop9Response;
import core.domain.maincontent.dto.MainPageContentResponse;
import core.domain.maincontent.service.MainContentService;
import core.domain.maincontent.service.search.MainContentKeywordExtractor;
import core.domain.post.service.MainContentSuggestIndex;
import core.global.docs.annotations.GlobalErrorDocs;
import core.global.docs.annotations.MainContentErrorDocs;
import core.global.enums.KNewsContentType;
import core.global.enums.MainContentSortOption;
import core.global.enums.errorcode.GlobalErrorCode;
import core.global.enums.errorcode.MainContentErrorCode;
import core.global.metrics.FeatureUsageMetrics;
import core.global.pagination.CursorPageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Main-Contents", description = "메인 콘텐츠 API")
@RestController
@RequestMapping("/api/v2/main-contents")
@GlobalErrorDocs({GlobalErrorCode.INTERNAL_SERVER_ERROR, GlobalErrorCode.INVALID_INPUT, GlobalErrorCode.INVALID_JSON, GlobalErrorCode.METHOD_NOT_ALLOWED})
public class MainContentController {

    private final MainContentService mainContentService;
    private final FeatureUsageMetrics featureUsageMetrics;
    private final MainContentController mainContentController;
    private final MainContentKeywordExtractor newsExtractor;
    private final MainContentSuggestIndex mainContentSuggestIndex;


    MainContentController(MainContentService mainContentService, FeatureUsageMetrics featureUsageMetrics, MainContentController mainContentController, MainContentKeywordExtractor newsExtractor, MainContentSuggestIndex mainContentSuggestIndex) {
        this.mainContentService = mainContentService;
        this.featureUsageMetrics = featureUsageMetrics;
        this.mainContentController = mainContentController;
        this.newsExtractor = newsExtractor;
        this.mainContentSuggestIndex = mainContentSuggestIndex;
    }

    @Operation(summary = "메인페이지 K-News 최신 3개 조회")
    @ApiResponses(
            @ApiResponse(
                    responseCode = "200",
                    description = "성공"
            )
    )
    @GetMapping("/{type}/preview")
    public ResponseEntity<core.global.dto.ApiResponse<List<MainContentNewsResponse>>> getTop3News(
            @PathVariable KNewsContentType type
    ) {
        featureUsageMetrics.recordMainPageUsage();

        return ResponseEntity.ok(
                core.global.dto.ApiResponse.success(
                        mainContentService.getTop3News(type)
                ));
    }

    @Operation(summary = "메인페이지 K-News 리스트 조회")
    @ApiResponses(
            @ApiResponse(
                    responseCode = "200",
                    description = "성공"
            )
    )
    @GetMapping("/{type}/list")
    public ResponseEntity<core.global.dto.ApiResponse<CursorPageResponse<MainContentNewsListResponse>>> getCategoryNews(
            @PathVariable KNewsContentType type,
            @Parameter(description = "정렬 옵션", example = "TRENDING") @RequestParam(defaultValue = "TRENDING") MainContentSortOption sort,
            @Parameter(description = "응답의 nextCursor를 그대로 입력(첫 페이지는 비움)", example = "eyJ0IjoiMjAyNS0wOC0yMVQxMjowMDowMFoiLCJpZCI6MTAxfQ")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "페이지 크기(1~50)", example = "20") @RequestParam(defaultValue = "20") int size
    ) {
        featureUsageMetrics.recordMainPageUsage();

        return ResponseEntity.ok(
                core.global.dto.ApiResponse.success(
                        mainContentService.getCategoryNews(type, sort, cursor, size)
                ));
    }

    /**
     * 메인 콘텐츠 단건 조회 API
     * URL: GET /api/v1/main-contents/{contentId}
     */
    @Operation(summary = "메인 콘텐츠 단건 조회", description = "콘텐츠 ID(pk)를 이용하여 메인 페이지에 표시될 콘텐츠의 상세 정보를 조회합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공"),
    })
    @GetMapping("/{contentId}")
    @MainContentErrorDocs({MainContentErrorCode.CONTENT_NOT_FOUND})
    public ResponseEntity<MainPageContentResponse> getMainContent(
            @Parameter(description = "조회할 콘텐츠의 ID", required = true, example = "1")
            @PathVariable Long contentId
    ) {
        MainPageContentResponse response = mainContentService.getMainContent(contentId);

        featureUsageMetrics.recordMainPageUsage();
        mainContentController.extractedKeyword(response.htmlContent(), 1);

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "메인페이지 Trending K-News 조회")
    @ApiResponses(
            @ApiResponse(
                    responseCode = "200",
                    description = "성공"
            )
    )
    @GetMapping("/trending")
    public ResponseEntity<core.global.dto.ApiResponse<List<MainContentTop9Response>>> getTrendingKNews() {
        featureUsageMetrics.recordMainPageUsage();
        return ResponseEntity.ok(
                core.global.dto.ApiResponse.success(
                        mainContentService.getTrendingKNews()
                ));
    }

    private void extractedKeyword(String content, int score) {
        if (content != null && !content.isBlank()) {
            // 상위 K만 반영
            int K = 8;
            var phrases = newsExtractor.extract(content, K);
            phrases.forEach(p -> mainContentSuggestIndex.upsert(p, score));
        }
    }

}

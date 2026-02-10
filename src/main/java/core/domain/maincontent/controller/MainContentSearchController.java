package core.domain.maincontent.controller;


import core.domain.maincontent.dto.MainContentsSearchResultView;
import core.domain.maincontent.dto.MainPageContentResponse;
import core.domain.maincontent.service.MainContentService;
import core.domain.maincontent.service.search.MainContentKeywordExtractor;
import core.domain.maincontent.service.search.MainContentRecentService;
import core.domain.maincontent.service.search.MainContentSearchService;
import core.domain.post.dto.search.SuggestClickRequest;
import core.domain.post.service.MainContentSuggestIndex;
import core.global.docs.annotations.CommunityErrorDocs;
import core.global.docs.annotations.GlobalErrorDocs;
import core.global.docs.annotations.MainContentErrorDocs;
import core.global.docs.annotations.UserErrorDocs;
import core.global.enums.errorcode.CommunityErrorCode;
import core.global.enums.errorcode.GlobalErrorCode;
import core.global.enums.errorcode.MainContentErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.metrics.FeatureUsageMetrics;
import core.global.pagination.CursorPageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Main-Contents Search", description = "메인 콘텐츠 검색 API")
@RestController
@RequestMapping("/api/v2/main-contents/search")
@GlobalErrorDocs({GlobalErrorCode.INTERNAL_SERVER_ERROR, GlobalErrorCode.INVALID_INPUT, GlobalErrorCode.INVALID_JSON, GlobalErrorCode.METHOD_NOT_ALLOWED})
public class MainContentSearchController {

    private final MainContentService mainContentService;
    private final MainContentSearchService mainContentSearchService;
    private final MainContentRecentService mainContentRecentService;
    private final MainContentSuggestIndex mainContentSuggestIndex;
    private final MainContentKeywordExtractor newsExtractor;
    private final FeatureUsageMetrics featureUsageMetrics;

    MainContentSearchController(MainContentService mainContentService, MainContentSearchService mainContentSearchService, MainContentRecentService mainContentRecentService, MainContentSuggestIndex mainContentSuggestIndex, MainContentKeywordExtractor newsExtractor, FeatureUsageMetrics featureUsageMetrics) {
        this.mainContentService = mainContentService;
        this.mainContentSearchService = mainContentSearchService;
        this.mainContentRecentService = mainContentRecentService;
        this.mainContentSuggestIndex = mainContentSuggestIndex;
        this.newsExtractor = newsExtractor;
        this.featureUsageMetrics = featureUsageMetrics;
    }

    @Operation(summary = "게시글 검색", description = "커서 페이지네이션 지원")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    @GetMapping("/posts")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    public ResponseEntity<core.global.dto.ApiResponse<CursorPageResponse<MainContentsSearchResultView>>> getPostList(
            @RequestParam String q,
            @Parameter(description = "응답의 nextCursor를 그대로 입력(첫 페이지는 비움)", example = "eyJ0IjoiMjAyNS0wOC0yMVQxMjowMDowMFoiLCJpZCI6MTAxfQ")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "페이지 크기(1~50)", example = "20") @RequestParam(defaultValue = "20") int size
    ) {
        featureUsageMetrics.recordMainPageUsage();

        mainContentRecentService.log(q);
        return ResponseEntity.ok(
                core.global.dto.ApiResponse.success(
                        mainContentSearchService.search(q, cursor, size)
                ));
    }


    @Operation(summary = "검색결과 상세페이지",
            description = "사용자가 검색결과를 클릭/열람했을 때 호출하여 인기(pop) 점수를 반영하고 상세페이지를 제공합니다.")
    @GetMapping("/posts/{contentId}")
    @MainContentErrorDocs({MainContentErrorCode.CONTENT_NOT_FOUND})
    public ResponseEntity<core.global.dto.ApiResponse<MainPageContentResponse>> resultClicked(
            @Parameter(description = "게시글 ID", example = "123") @PathVariable @Positive Long contentId
    ) {
        MainPageContentResponse contentDetail = mainContentService.getMainContent(contentId);


        featureUsageMetrics.recordMainPageUsage();

        return ResponseEntity.ok(core.global.dto.ApiResponse.success(contentDetail));
    }

    @Operation(summary = "자동완성 제안", description = "메모리+DB 하이브리드")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    @GetMapping("/suggest")
    public List<String> suggestByBoard(@RequestParam("q") String q) {
        return mainContentSearchService.suggest(q);
    }

    @Operation(summary = "메인 콘텐츠 무작위 추천 키워드 조회",
            description = "상위 100개 키워드 중 무작위로 6개를 뽑아 반환 (Refresh 버튼용)")
    @GetMapping("/hot-keywords")
    public ResponseEntity<core.global.dto.ApiResponse<List<String>>> getHotKeywords() {
        // 뉴스 도메인에 특화된 인기 키워드 반환
        List<String> keywords = mainContentSearchService.getRandomHotKeywords();
        return ResponseEntity.ok(core.global.dto.ApiResponse.success(keywords));
    }

    @Operation(summary = "추천 키워드 클릭 후 검색",
            description = "추천 칩 클릭 시 해당 키워드의 점수를 올립니다.")
    @PostMapping("/hot-keywords/clicked")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    public ResponseEntity<core.global.dto.ApiResponse<CursorPageResponse<MainContentsSearchResultView>>> recommendationClicked(
            @RequestParam String keyword,
            @Parameter(description = "응답의 nextCursor를 그대로 입력(첫 페이지는 비움)", example = "eyJ0IjoiMjAyNS0wOC0yMVQxMjowMDowMFoiLCJpZCI6MTAxfQ")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "페이지 크기(1~50)", example = "20") @RequestParam(defaultValue = "20") int size
    ) {
        // 1. 점수 올리기 (DB frequency + 1)
        mainContentSearchService.increaseRecommendationScore(keyword);

        // 2. 검색 기록 로깅 (최근 검색어에도 추가하고 싶다면)
        mainContentRecentService.log(keyword);

        featureUsageMetrics.recordMainPageUsage();

        return ResponseEntity.ok(
                core.global.dto.ApiResponse.success(
                        mainContentSearchService.search(keyword, cursor, size)
                )
        );
    }


    @Operation(summary = "자동완성 클릭 기록")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    @PostMapping("/clicked")
    public void clicked(@RequestBody @Valid SuggestClickRequest body) {
        featureUsageMetrics.recordMainPageUsage();
        extractedKeyword(body.text(), 1);
    }

    @Operation(summary = "최근 검색어 조회")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    @GetMapping("/recent")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    public List<String> recent() {
        return mainContentRecentService.list();
    }

    @Operation(summary = "최근 검색어 단건 삭제")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    @DeleteMapping("/recent")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    public void deleteRecent(@RequestParam String q) {
        mainContentRecentService.remove(q);
    }

    @Operation(summary = "최근 검색어 전체 삭제")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    @DeleteMapping("/recent/all")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    public void clearRecent() {
        mainContentRecentService.clear();
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

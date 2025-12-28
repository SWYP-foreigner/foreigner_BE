package core.domain.maincontent.controller;

import core.domain.maincontent.dto.MainContentTop3Response;
import core.domain.maincontent.dto.MainContentTop9Response;
import core.domain.maincontent.entity.KNewsContentType;
import core.domain.maincontent.dto.MainPageContentResponse;
import core.domain.maincontent.service.MainContentService;

import core.global.docs.annotations.MainContentErrorDocs;
import core.global.enums.errorcode.MainContentErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v2/main-contents") // 기본 경로 설정
public class MainContentController {

    private final MainContentService mainContentService;

    MainContentController(MainContentService mainContentService) {
        this.mainContentService = mainContentService;
    }

    @Operation(summary = "메인페이지 K-News 최신 3개 조회")
    @ApiResponses(
            @ApiResponse(
                    responseCode = "200",
                    description = "성공"
            )
    )
    @GetMapping("/{type}/preview")
    public ResponseEntity<core.global.dto.ApiResponse<List<MainContentTop3Response>>> getTop3News(
            @PathVariable KNewsContentType type
    /**
     * 메인 콘텐츠 단건 조회 API
     * URL: GET /api/v1/main-contents/{contentId}
     */
    @Operation(summary = "메인 콘텐츠 단건 조회", description = "콘텐츠 ID(pk)를 이용하여 메인 페이지에 표시될 콘텐츠의 상세 정보를 조회합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "존재하지 않는 콘텐츠 ID")
    })
    @GetMapping("/{contentId}")
    @MainContentErrorDocs({MainContentErrorCode.CONTENT_NOT_FOUND})
    public ResponseEntity<MainPageContentResponse> getMainContent(
            @Parameter(description = "조회할 콘텐츠의 ID", required = true, example = "1")
            @PathVariable Long contentId
    ) {


        return ResponseEntity.ok(
                core.global.dto.ApiResponse.success(
                        mainContentService.getTop3News(type)
                ));
        MainPageContentResponse response = mainContentService.getMainContent(contentId);
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

        return ResponseEntity.ok(
                core.global.dto.ApiResponse.success(
                        mainContentService.getTrendingKNews()
                ));
    }

}

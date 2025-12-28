package core.domain.mainpage.controller;

import core.domain.board.dto.BoardItem;
import core.domain.mainpage.service.KNewsContentType;
import core.domain.mainpage.service.KoreanNewsResponse;
import core.domain.mainpage.service.KoreanNewsService;
import core.domain.mainpage.service.PollService;
import core.domain.post.service.PostService;
import core.global.enums.SortOption;
import core.global.metrics.FeatureUsageMetrics;
import core.global.pagination.CursorPageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class KoreanNewsController {
    private final KoreanNewsService koreanNewsService;

    KoreanNewsController(KoreanNewsService koreanNewsService) {
        this.koreanNewsService = koreanNewsService;
    }

    @Operation(summary = "메인페이지 K-News 최신 3개 조회")
    @ApiResponses(
            @ApiResponse(
                    responseCode = "200",
                    description = "성공"
            )
    )
    @GetMapping("/{type}/preview")
    public ResponseEntity<core.global.dto.ApiResponse<List<KoreanNewsResponse>>> getTop3News(
            @PathVariable KNewsContentType type
    ) {


        return ResponseEntity.ok(
                core.global.dto.ApiResponse.success(
                        koreanNewsService.getTop3News(type)
                ));
    }

}

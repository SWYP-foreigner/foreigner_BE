package core.domain.mainpage.controller;

import core.domain.mainpage.dto.PollDetailResponse;
import core.domain.mainpage.service.PollService;
import core.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Poll", description = "퀴즈 & 투표 API")
public class PollController {
    private final PollService pollService;

    PollController(PollService pollService) {
        this.pollService = pollService;
    }

    @Operation(summary = "투표 상세", description = "투표의 상세 페이지")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "성공"
    )
    @GetMapping("/poll/{pollId}")
    public ResponseEntity<ApiResponse<PollDetailResponse>> getPollDetail(
            @Parameter(description = "투표 ID", example = "123")
            @PathVariable @Positive Long pollId
            ) {

        return ResponseEntity.ok(core.global.dto.ApiResponse.success(
                pollService.getPollDetail(pollId)
        ));
    }

}

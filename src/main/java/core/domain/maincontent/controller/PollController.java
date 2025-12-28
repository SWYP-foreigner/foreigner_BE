package core.domain.maincontent.controller;

import core.domain.maincontent.dto.PollItem;
import core.domain.maincontent.dto.PollParticipateRequest;
import core.domain.maincontent.dto.PollResultResponse;
import core.domain.maincontent.entity.PollType;
import core.domain.maincontent.service.PollService;
import core.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Poll", description = "퀴즈 & 투표 API")
public class PollController {
    private final PollService pollService;

    PollController(PollService pollService) {
        this.pollService = pollService;
    }

    @Operation(summary = "오늘의 투표/퀴즈 조회", description = "최신 투표 또는 퀴즈를 1건 조회합니다.")
    @GetMapping("/today")
    public ResponseEntity<ApiResponse<PollItem>> getTodayPoll(
            @RequestParam PollType type // VOTE 또는 QUIZ
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                pollService.getTodayPoll(type)
        ));
    }

    @PostMapping("/poll/{pollId}/participate")
    public ResponseEntity<ApiResponse<PollResultResponse>> participate(
            @PathVariable Long pollId,
            @RequestBody PollParticipateRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                pollService.participate(pollId, request.optionId())
        ));
    }
}

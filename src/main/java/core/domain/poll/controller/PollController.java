package core.domain.poll.controller;

import core.domain.poll.dto.PollItem;
import core.domain.poll.dto.PollParticipateRequest;
import core.domain.poll.dto.PollResultResponse;
import core.domain.poll.service.PollService;
import core.global.enums.PollType;
import core.global.docs.annotations.CommunityErrorDocs;
import core.global.docs.annotations.UserErrorDocs;
import core.global.dto.ApiResponse;
import core.global.enums.errorcode.CommunityErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    @Operation(summary = "오늘의 투표/퀴즈 조회", description = "최신 투표 또는 퀴즈를 1건 조회합니다.")
    @GetMapping("/today")
    @CommunityErrorDocs({CommunityErrorCode.POLL_NOT_FOUND})
    public ResponseEntity<ApiResponse<PollItem>> getTodayPoll(
            @RequestParam PollType type // VOTE 또는 QUIZ
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                pollService.getTodayPoll(type)
        ));
    }

    @Operation(summary = "투표, 퀴즈 참여 후 결과 조회", description = "투표, 퀴즈 참여 후 결과를 제공합니다.")
    @PostMapping("/poll/{pollId}/participate")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    @CommunityErrorDocs({CommunityErrorCode.POLL_NOT_FOUND, CommunityErrorCode.POLL_ALREADY_CLOSED, CommunityErrorCode.POLL_ALREADY_CLOSED, CommunityErrorCode.ALREADY_PARTICIPATED})
    public ResponseEntity<ApiResponse<PollResultResponse>> participate(
            @PathVariable Long pollId,
            @RequestBody PollParticipateRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                pollService.participate(pollId, request.optionId())
        ));
    }
}

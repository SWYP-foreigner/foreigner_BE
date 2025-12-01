package core.global.userfeedback;

 // 커스텀 에러 응답 DTO
import core.global.userfeedback.dto.FeedbackEligibilityResponse;
import core.global.userfeedback.dto.FeedbackRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.*;

@Tag(name = "User Feedback", description = "유저 피드백 및 만족도 조사 API")
@RestController
@RequestMapping("/api/v1/feedbacks")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    @Operation(summary = "피드백 작성 가능 여부 확인", description = "유저가 이미 피드백을 작성했는지 확인합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "사용자 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "USER_NOT_FOUND", value = """
                                    {
                                        "code": "USER_NOT_FOUND",
                                        "message": "사용자 정보를 찾을 수 없습니다.",
                                        "status": 404
                                    }
                                    """)))
    })
    @GetMapping("/eligibility")
    public ResponseEntity<FeedbackEligibilityResponse> checkEligibility(
            @AuthenticationPrincipal Long userId
    ) {
        boolean isEligible = feedbackService.checkEligibility(userId);
        return ResponseEntity.ok(new FeedbackEligibilityResponse(isEligible));
    }
    @Operation(summary = "피드백 제출", description = "유저의 피드백을 저장합니다. (1인 1회 제한)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "제출 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 입력값 (내용 누락, 길이 제한 초과 등)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(
                                    name = "INVALID_INPUT_VALUE",
                                    summary = "필수 값 누락 예시",
                                    value = """
                                    {
                                        "code": "INVALID_INPUT_VALUE",
                                        "message": "content: 내용은 필수입니다.",
                                        "status": 400
                                    }
                                    """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "이미 제출한 사용자",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(
                                    name = "ALREADY_SUBMITTED",
                                    value = """
                                    {
                                        "code": "ALREADY_SUBMITTED",
                                        "message": "이미 피드백을 제출한 사용자입니다.",
                                        "status": 409
                                    }
                                    """
                            )
                    )
            )
    })
    @PostMapping
    public ResponseEntity<Void> submitFeedback(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid FeedbackRequest request
    ) {
        feedbackService.createFeedback(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
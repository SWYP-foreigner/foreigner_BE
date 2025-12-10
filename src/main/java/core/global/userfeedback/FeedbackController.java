package core.global.userfeedback;


import core.global.config.CustomUserDetails;
import core.global.docs.annotations.UserErrorDocs;
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
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.*;

@Tag(name = "User Feedback", description = "유저 피드백 및 만족도 조사 API")
@RestController
@RequestMapping("/api/v1/feedbacks")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    @Operation(summary = "피드백 제출", description = "유저의 피드백을 저장합니다. (횟수 제한 없음)")
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
            )
    })
    @PostMapping
    public ResponseEntity<Void> submitFeedback(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestBody @Valid FeedbackRequest request
    ) {
        feedbackService.createFeedback(principal.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
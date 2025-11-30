package core.global.userfeedback;


import core.global.userfeedback.dto.FeedbackEligibilityResponse;
import core.global.userfeedback.dto.FeedbackRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/feedbacks")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    @GetMapping("/eligibility")
    public ResponseEntity<FeedbackEligibilityResponse> checkEligibility(
            @AuthenticationPrincipal Long userId
    ) {
        boolean isEligible = feedbackService.checkEligibility(userId);
        return ResponseEntity.ok(new FeedbackEligibilityResponse(isEligible));
    }
    @PostMapping
    public ResponseEntity<Void> submitFeedback(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid FeedbackRequest request
    ) {
        feedbackService.createFeedback(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
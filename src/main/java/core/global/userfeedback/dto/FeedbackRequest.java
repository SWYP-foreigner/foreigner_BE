package core.global.userfeedback.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

public record FeedbackRequest(

        @Schema(description = "피드백 내용", example = "앱이 너무 사용하기 편해요!")
        @NotBlank(message = "내용은 필수입니다.")
        @Size(max = 1000, message = "내용은 1000자 이내여야 합니다.")
        String content,

        @Schema(description = "유입 경로", example = "HOME_BANNER")
        String source
) {}
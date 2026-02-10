package core.domain.poll.controller;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "QuizUpdateRequest", description = "퀴즈 수정 요청")
public record QuizUpdateRequest(
        @Schema(description = "퀴즈 ID", example = "1")
        Long id,

        @Schema(description = "질문 내용 (핵심 질문)", example = "대한민국의 수도는 어디인가요?", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String title,

        @Schema(description = "퀴즈 추가 설명 (선택 사항)", example = "지리 관련 상식 퀴즈입니다.", nullable = true)
        String description,

        @Schema(description = "퀴즈 상세 본문", example = "아래 보기 중 하나를 선택하세요.")
        String content
) {
}

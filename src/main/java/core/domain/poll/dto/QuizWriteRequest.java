package core.domain.poll.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(name = "QuizWriteRequest", description = "퀴즈 생성 요청")
public record QuizWriteRequest(
        @Schema(description = "질문 내용 (핵심 질문)", example = "대한민국의 수도는 어디인가요?", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String title,

        @Schema(description = "퀴즈 추가 설명 (선택 사항)", example = "지리 관련 상식 퀴즈입니다.", nullable = true)
        String description,

        @Schema(description = "퀴즈 상세 본문", example = "아래 보기 중 하나를 선택하세요.")
        String content,

        @Schema(
                description = "퀴즈 선택지 목록 (최소 2개 이상)",
                example = "[\"부산\", \"서울\", \"대구\", \"광주\"]",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotEmpty
        @Size(min = 2)
        List<String> options,

        @Schema(
                description = "정답 옵션의 인덱스 (0부터 시작, options 리스트의 순서와 일치해야 함)",
                example = "1",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        int correctOptionIndex
) {
}
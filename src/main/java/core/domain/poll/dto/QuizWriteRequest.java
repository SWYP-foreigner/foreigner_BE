package core.domain.poll.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record QuizWriteRequest(
        String content,
        String title,
        String description,
        List<String> options,
        @Schema(description = "정답 옵션의 인덱스 (0부터 시작)", example = "1")
        int correctOptionIndex
) {
}

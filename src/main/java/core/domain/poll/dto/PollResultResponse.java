package core.domain.poll.dto;

import core.global.enums.PollType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "PollResultResponse", description = "투표 또는 퀴즈 결과 응답")
public record PollResultResponse(
        @Schema(description = "투표 ID", example = "50", requiredMode = Schema.RequiredMode.REQUIRED)
        Long pollId,

        @Schema(description = "투표 유형 (QUIZ, SURVEY 등)", requiredMode = Schema.RequiredMode.REQUIRED)
        PollType type,

        @Schema(description = "퀴즈 정답 여부 (퀴즈 유형일 때만 유효)", example = "true", nullable = true)
        Boolean isCorrect,

        @Schema(description = "퀴즈 정답 항목 ID (사용자가 틀렸을 경우 정답 안내용)", example = "2", nullable = true)
        Long correctOptionId,

        @Schema(description = "각 항목별 득표 결과 리스트", requiredMode = Schema.RequiredMode.REQUIRED)
        List<OptionResult> results
) {
    @Schema(name = "PollOptionResult", description = "투표 항목별 결과(득표수 및 비율)")
    public record OptionResult(
            @Schema(description = "항목 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
            Long optionId,

            @Schema(description = "해당 항목의 총 득표 수", example = "460", requiredMode = Schema.RequiredMode.REQUIRED)
            long voteCount,

            @Schema(description = "득표율 (%)", example = "46.0", requiredMode = Schema.RequiredMode.REQUIRED)
            double percentage
    ) {}
}
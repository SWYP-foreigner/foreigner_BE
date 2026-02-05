
package core.domain.poll.dto;

import core.global.enums.PollType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(name = "PollItem", description = "투표 상세 정보")
public record PollItem(
        @Schema(description = "투표 ID", example = "50", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,

        @Schema(description = "투표 유형 (예: SINGLE_CHOICE, OX_QUIZ 등)", requiredMode = Schema.RequiredMode.REQUIRED)
        PollType type,

        @Schema(description = "투표 질문 내용", example = "가장 선호하는 개발 언어는 무엇인가요?", requiredMode = Schema.RequiredMode.REQUIRED)
        String title,

        @Schema(description = "투표 하단 상세 설명", example = "자유롭게 본인의 선호를 투표해주세요.", nullable = true)
        String description,

        @Schema(description = "투표 마감 시간(UTC)", type = "string", format = "date-time", example = "2025-12-31T23:59:59Z", requiredMode = Schema.RequiredMode.REQUIRED)
        Instant closeAt,

        @Schema(description = "전체 참여자 수", example = "1250", requiredMode = Schema.RequiredMode.REQUIRED)
        long totalVoteCount,

        @Schema(description = "투표 선택지 목록", requiredMode = Schema.RequiredMode.REQUIRED)
        List<OptionItem> options,

        @Schema(description = "현재 사용자가 선택한 항목 ID (미참여 시 null)", nullable = true, example = "1")
        Long selectedOptionId,

        @Schema(description = "정답 정보 옵션 ID", example = "1")
        Long correctOptionId
) {
    @Schema(name = "PollOptionItem", description = "투표 선택지 상세 정보")
    public record OptionItem(
            @Schema(description = "선택지 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
            Long id,

            @Schema(description = "선택지 텍스트 내용", example = "Java", requiredMode = Schema.RequiredMode.REQUIRED)
            String content,

            @Schema(description = "해당 항목의 득표 수", example = "450", requiredMode = Schema.RequiredMode.REQUIRED)
            long voteCount
    ) {}
}
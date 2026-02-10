package core.domain.poll.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

@Schema(name = "PollParticipateRequest", description = "투표 참여 요청")
public record PollParticipateRequest(
        @Schema(
                description = "선택한 투표 항목(Option)의 ID",
                example = "1",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @Positive
        Long optionId
) {}
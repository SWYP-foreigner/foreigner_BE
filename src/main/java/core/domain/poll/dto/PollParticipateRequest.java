package core.domain.poll.dto;

import jakarta.validation.constraints.Positive;

public record PollParticipateRequest(
        @Positive Long optionId
) {}
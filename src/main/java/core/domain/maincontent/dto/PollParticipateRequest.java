package core.domain.maincontent.dto;

import jakarta.validation.constraints.Positive;

public record PollParticipateRequest(
        @Positive Long optionId
) {}
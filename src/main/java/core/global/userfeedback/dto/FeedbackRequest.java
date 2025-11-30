package core.global.userfeedback.dto;

import core.global.enums.FeedbackSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FeedbackRequest(
        @NotBlank(message = "내용을 입력해주세요.")
        @Size(max = 2000, message = "2000자 이내로 입력해주세요.")
        String content,

        FeedbackSource source // 프론트에서 보내줌 (CHAT, COMMUNITY...)
) {}
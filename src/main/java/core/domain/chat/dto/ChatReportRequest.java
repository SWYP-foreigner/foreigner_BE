package core.domain.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ChatReportRequest(
        @NotNull Long messageId,      // 문제의 메시지 ID
        @NotBlank String reasonCategory, // "SPAM", "HARASSMENT", "ETC" 등 선택형
        String reasonDetail          // 추가 서술형 내용
) {}

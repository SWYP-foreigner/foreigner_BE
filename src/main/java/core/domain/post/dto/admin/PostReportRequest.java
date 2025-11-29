package core.domain.post.dto.admin;

import jakarta.validation.constraints.NotBlank;

public record PostReportRequest(
        @NotBlank(message = "신고 사유 카테고리는 필수입니다.")
        String reasonCategory,

        String reasonDetail
) {}

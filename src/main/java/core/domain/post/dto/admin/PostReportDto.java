package core.domain.post.dto.admin;

import core.domain.post.entity.PostReport;

import java.time.Instant;

public record PostReportDto(
        Long reportId,
        Long reporterId,
        String reporterName,
        Long reportedId,
        String reportedName,
        Long postId,
        String postTitleSnapshot,
        String reasonCategory,
        String reasonDetail,
        Instant createdAt
) {
    public static PostReportDto from(PostReport report) {

        String reporterName = report.getReporter().getFirstName() + " " + report.getReporter().getLastName();
        String reportedName = report.getReportedUser().getFirstName() + " " + report.getReportedUser().getLastName();

        return new PostReportDto(
                report.getId(),
                report.getReporter().getId(),
                reporterName.trim(),
                report.getReportedUser().getId(),
                reportedName.trim(),
                report.getPost().getId(),
                report.getPostTitleSnapshot(),
                report.getReasonCategory(),
                report.getReasonDetail(),
                report.getCreatedAt()
        );
    }
}

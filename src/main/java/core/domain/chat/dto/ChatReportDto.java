package core.domain.chat.dto;

import core.domain.chat.entity.ChatReport;

import java.time.Instant;

public record ChatReportDto(
        Long reportId,
        Long chatRoomId,
        Long messageId,
        Long reporterId,
        String reporterName,
        Long reportedId,
        String reportedName,
        String messageContent,
        String reasonCategory,
        String reasonDetail,
        Instant createdAt
) {
    public static ChatReportDto from(ChatReport report) {
        return new ChatReportDto(
                report.getId(),
                report.getChatRoom().getId(),
                report.getMessageId(),
                report.getReporterUser().getId(),
                report.getReporterUser().getName(),
                report.getReportedUser().getId(),
                report.getReportedUser().getName(),
                report.getMessageContent(),
                report.getReasonCategory(),
                report.getReasonDetail(),
                report.getCreatedAt()
        );
    }
}

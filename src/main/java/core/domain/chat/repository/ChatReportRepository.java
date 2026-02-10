package core.domain.chat.repository;

import core.domain.chat.entity.ChatReport;
import core.global.enums.chat.ChatReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatReportRepository extends JpaRepository<ChatReport, Long> {

    Page<ChatReport> findByStatus(ChatReportStatus status, Pageable pageable);

    boolean existsByReporterUserIdAndMessageId(Long reporterUserId, Long messageId);
}
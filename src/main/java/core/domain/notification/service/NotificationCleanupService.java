package core.domain.notification.service;

import core.domain.notification.entity.Notification;
import core.domain.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationCleanupService {

    private final NotificationRepository notificationRepository;
    private static final int BATCH_SIZE = 1000;

    @Scheduled(cron = "0 0 4 * * *")
    public void scheduledDeleteOldNotifications() {
        log.info("[BATCH] 오래된 알림 데이터 삭제 작업을 시작합니다.");
        Instant oneWeekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        long totalDeleted = 0;

        try {
            PageRequest pageRequest = PageRequest.of(0, BATCH_SIZE);
            Slice<Notification> batch;

            do {
                int deletedCountInBatch = deleteBatch(oneWeekAgo, pageRequest);

                totalDeleted += deletedCountInBatch;

                if (deletedCountInBatch < BATCH_SIZE) {
                    break;
                }

                log.info("[BATCH] {}건 삭제 완료. (총 {}건 삭제)", deletedCountInBatch, totalDeleted);

                Thread.sleep(1000);

            } while (true);

            log.info("[BATCH] 오래된 알림 데이터 삭제 작업 완료. 총 {}건 삭제.", totalDeleted);

        } catch (Exception e) {
            log.error("[BATCH] 오래된 알림 데이터 삭제 작업 중 오류 발생", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Transactional
    public int deleteBatch(Instant cutoffDate, Pageable pageable) {
        Slice<Notification> notificationsToDelete = notificationRepository.findByCreatedAtBefore(cutoffDate, pageable);

        List<Notification> content = notificationsToDelete.getContent();
        if (content.isEmpty()) {
            return 0;
        }

        notificationRepository.deleteAllInBatch(content);

        return content.size();
    }
}

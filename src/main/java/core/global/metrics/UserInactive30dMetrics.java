package core.global.metrics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
@RequiredArgsConstructor
public class UserInactive30dMetrics {

    private final UserMetricService userMetricService; // 서비스 주입
    private final AtomicLong inactive30d = new AtomicLong();
    private final AtomicLong totalUsers = new AtomicLong();

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        log.info("[U30d] warmup start");
        try {
            // 이제 다른 Bean을 호출하므로 @Transactional이 정상 작동합니다.
            collect();
        } catch (Exception e) {
            log.warn("[U30d] warmup failed", e);
        }

        inactive30d.get();
        totalUsers.get();
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT30S")
    public void collect() {
        try {
            // 서비스 레이어를 통해 데이터 조회 (트랜잭션 보장)
            Object[] row = userMetricService.getInactiveAndTotalCounts();

            if (row.length < 2) {
                log.warn("[U30d] unexpected row size: {}", row.length);
                return;
            }

            long inactive = ((Number) row[0]).longValue();
            long total = ((Number) row[1]).longValue();

            inactive30d.set(inactive);
            totalUsers.set(total);

            log.debug("[U30d] updated: inactive={}, total={}", inactive, total);

        } catch (Exception e) {
            log.error("[U30d] collect failed", e);
        }
    }
}
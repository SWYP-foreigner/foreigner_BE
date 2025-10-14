package core.global.metrics;

import core.domain.user.repository.UserRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class UserInactive30dMetrics {

    private final UserRepository userRepository;

    private final AtomicInteger inactive30d = new AtomicInteger(0);
    private final AtomicInteger totalUsers  = new AtomicInteger(0);

    public UserInactive30dMetrics(MeterRegistry registry, UserRepository userRepository) {
        this.userRepository = userRepository;
        Gauge.builder("app_user_inactive_30d", inactive30d, AtomicInteger::get)
                .description("Users inactive for 30d+")
                .register(registry);

        Gauge.builder("app_user_total", totalUsers, AtomicInteger::get)
                .description("Total users")
                .register(registry);
    }

    @EventListener(ApplicationReadyEvent.class) // 기동 직후 1회 실행 → Explore에서 즉시 보이게
    public void warmup() {
        log.info("[UserInactive30dMetrics] warmup start");
        try { collect(); }
        catch (Exception e) { log.warn("warmup collect failed", e); }
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT30S")
    @Transactional(readOnly = true)
    public void collect() {
        try {
            Object result = userRepository.countInactive30dAndTotal();

            // result를 안전하게 1행 2컬럼으로 풀기
            Object[] row;
            if (result instanceof Object[] arr) {
                row = arr;
            } else if (result instanceof java.util.Collection<?> col) {
                if (col.isEmpty()) {
                    log.warn("countInactive30dAndTotal() returned empty collection");
                    return;
                }
                Object first = col.iterator().next();
                if (!(first instanceof Object[] inner)) {
                    log.warn("Unexpected element type: {}", first == null ? "null" : first.getClass());
                    return;
                }
                row = inner;
            } else {
                log.warn("Unexpected result type: {}", result == null ? "null" : result.getClass());
                return;
            }

            if (row.length < 2) {
                log.warn("Result columns < 2 : len={}", row.length);
                return;
            }

            int ina = toInt(row[0]);
            int tot = toInt(row[1]);

            inactive30d.set(ina);
            totalUsers.set(tot);

            log.debug("user_inactive_30d={}, user_total={}", ina, tot);
        } catch (Exception e) {
            log.error("Failed to update user_inactive_30d/user_total", e);
        }
    }

    private int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); }
        catch (Exception ignore) { return 0; }
    }
}

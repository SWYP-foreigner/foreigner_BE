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

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        log.info("[U30d] warmup start");
        try {
            Object res = userRepository.countInactive30dAndTotal();
            log.info("[U30d] warmup DB result = {}", java.util.Arrays.deepToString(
                    res instanceof Object[] a ? new Object[]{a} :
                            res instanceof java.util.Collection<?> c ? c.toArray() :
                                    new Object[]{res}
            ));
            collect();
        } catch (Exception e) {
            log.warn("[U30d] warmup failed", e);
        }
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT10S") // 일단 1분/10초로 빨리 확인
    @Transactional(readOnly = true)
    public void collect() {
        try {
            var rows = userRepository.countInactive30dAndTotal();
            if (rows == null || rows.isEmpty()) { log.warn("no rows"); return; }
            Object[] row = rows.get(0);
            int ina = toInt(row[0]);
            int tot = toInt(row[1]);
            inactive30d.set(ina);
            totalUsers.set(tot);

        } catch (Exception e) {
            log.error("[U30d] collect failed", e);
        }
    }

    private int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); }
        catch (Exception ignore) { return 0; }
    }
}

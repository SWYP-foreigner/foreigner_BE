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
// @Transactional(readOnly = true)  // 우선 제거해서 프록시/자기호출 이슈 배제
    public void collect() {
        try {
            Object res = userRepository.countInactive30dAndTotal();
            log.info("[U30d] raw type={}", (res==null ? "null" : res.getClass().getName()));

            Object[] row;
            if (res instanceof Object[] arr) {
                row = arr;
            } else if (res instanceof java.util.Collection<?> col) {
                if (col.isEmpty()) { log.warn("[U30d] empty collection"); return; }
                Object first = col.iterator().next();
                row = (first instanceof Object[] inner) ? inner : new Object[]{};
            } else { log.warn("[U30d] unexpected result"); return; }

            if (row.length < 2) { log.warn("[U30d] cols<2 len={}", row.length); return; }

            int ina = toInt(row[0]);
            int tot = toInt(row[1]);
            log.info("[U30d] parsed inactive30d={}, total={}", ina, tot);

            inactive30d.set(ina);
            totalUsers.set(tot);
            log.info("[U30d] set OK");
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

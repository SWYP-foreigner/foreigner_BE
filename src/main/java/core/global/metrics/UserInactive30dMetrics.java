package core.global.metrics;

import core.domain.user.repository.UserRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class UserInactive30dMetrics {

    private final UserRepository userRepository;

    private final AtomicInteger inactive30d = new AtomicInteger(0);
    private final AtomicInteger totalUsers  = new AtomicInteger(0);

    public UserInactive30dMetrics(MeterRegistry registry, UserRepository userRepository) {
        this.userRepository = userRepository;
        Gauge.builder("user_inactive_30d", inactive30d, AtomicInteger::get)
                .description("Users inactive for 30d+").register(registry);
        Gauge.builder("user_total", totalUsers, AtomicInteger::get)
                .description("Total users").register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() { collect(); }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    @Transactional(readOnly = true)
    public void collect() {
        Object[] row = userRepository.countInactive30dAndTotal();
        if (row == null || row.length < 2) return;
        Number ina = (Number) row[0];
        Number tot = (Number) row[1];
        inactive30d.set(ina != null ? ina.intValue() : 0);
        totalUsers.set(tot != null ? tot.intValue() : 0);
    }
}

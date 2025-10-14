package core.global.metrics;

import core.domain.user.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
public class UserInactiveMetrics {

    private final UserRepository userRepository;
    private final MultiGauge inactiveGauge;

    public UserInactiveMetrics(MeterRegistry registry, UserRepository userRepository) {
        this.userRepository = userRepository;
        this.inactiveGauge = MultiGauge.builder("user_inactivity_hours")
                .description("Number of users by inactivity buckets (hours since lastSeenAt)")
                .register(registry);
    }

    @EventListener(ApplicationReadyEvent.class) // 기동 직후 1회
    public void warmup() { try { collect(); } catch (Exception ignore) {} }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT30S")
    @Transactional(readOnly = true)
    public void collect() {
        Object[] row = userRepository.countInactiveBuckets();
        if (row == null || row.length < 6) return;
        Number[] n = Arrays.stream(row).map(x -> (Number) x).toArray(Number[]::new);

        inactiveGauge.register(List.of(
                MultiGauge.Row.of(Tags.of("le","24"),   n[0].doubleValue()),
                MultiGauge.Row.of(Tags.of("le","72"),   n[1].doubleValue()),
                MultiGauge.Row.of(Tags.of("le","168"),  n[2].doubleValue()),
                MultiGauge.Row.of(Tags.of("le","336"),  n[3].doubleValue()),
                MultiGauge.Row.of(Tags.of("le","720"),  n[4].doubleValue()),
                MultiGauge.Row.of(Tags.of("le","+Inf"), n[5].doubleValue())
        ), true);
    }
}

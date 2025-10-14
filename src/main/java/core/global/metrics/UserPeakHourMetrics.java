package core.global.metrics;

import core.domain.user.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Component
public class UserPeakHourMetrics {

    private final UserRepository userRepository;
    private final MultiGauge peakGauge; // user_last_seen_hour{hour="0..23"}

    public UserPeakHourMetrics(UserRepository userRepository, MeterRegistry registry) {
        this.userRepository = userRepository;
        this.peakGauge = MultiGauge.builder("user_last_seen_hour")
                .description("Last seen distribution by hour of day (last 7d)")
                .register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() { collect(); }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    @Transactional(readOnly = true)
    public void collect() {
        List<Object[]> rows = userRepository.lastSeenHourDist7d();
        List<MultiGauge.Row<?>> out = new ArrayList<>();
        for (Object[] r : rows) {
            String hour = String.valueOf(((Number) r[0]).intValue()); // "0"~"23"
            double cnt = ((Number) r[1]).doubleValue();
            out.add(MultiGauge.Row.of(Tags.of("hour", hour), cnt));
        }
        peakGauge.register(out, true);
    }
}
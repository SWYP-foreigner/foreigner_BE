package core.global.metrics;

import core.domain.user.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class UserInactiveMetrics {

    private final UserRepository userRepository;
    private final MultiGauge inactiveGauge;

    // MultiGauge 빈 등록
    public UserInactiveMetrics(MeterRegistry registry, UserRepository userRepository) {
        this.userRepository = userRepository;
        this.inactiveGauge = MultiGauge.builder("user_inactivity_hours")
                .description("Number of users by inactivity buckets (hours since lastSeenAt)")
                .register(registry);
    }

    @PostConstruct
    public void init() {
        collect();                 // ← 시작 시 1회 등록
    }

    @Scheduled(fixedDelayString = "PT2M") // 2분마다
    public void collect() {
        Object[] row = userRepository.countInactiveBuckets();
        if (row == null) return;

        // 순서: 0:0-24, 1:24-72, 2:72-168, 3:168-336, 4:336-720, 5:720+
        Number[] nums = new Number[row.length];
        for (int i = 0; i < row.length; i++) nums[i] = (Number) row[i];

        inactiveGauge.register(
                java.util.List.of(
                        MultiGauge.Row.of(Tags.of("le", "24"),      nums[0].doubleValue()),
                        MultiGauge.Row.of(Tags.of("le", "72"),      nums[1].doubleValue()),
                        MultiGauge.Row.of(Tags.of("le", "168"),     nums[2].doubleValue()),
                        MultiGauge.Row.of(Tags.of("le", "336"),     nums[3].doubleValue()),
                        MultiGauge.Row.of(Tags.of("le", "720"),     nums[4].doubleValue()),
                        MultiGauge.Row.of(Tags.of("le", "+Inf"),    nums[5].doubleValue())
                ),
                true // replace existing
        );
    }
}
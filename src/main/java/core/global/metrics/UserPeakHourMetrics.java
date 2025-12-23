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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class UserPeakHourMetrics {

    private final UserMetricService userMetricService;
    private final Map<Integer, AtomicInteger> buckets = new ConcurrentHashMap<>();
    private final MultiGauge peakGauge; // user_last_seen_hour{hour="0..23"}

    public UserPeakHourMetrics(UserMetricService userMetricService, MeterRegistry registry) {
        this.userMetricService = userMetricService;
        this.peakGauge = MultiGauge.builder("user_peak_hour_dist")
                .description("Last 7d distribution by hour")
                .register(registry);

        // 0~23시 초기화
        for (int i = 0; i < 24; i++) {
            buckets.put(i, new AtomicInteger(0));
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        collect();
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    public void collect() {
        // 1) 전 시간대 0으로 초기화
        buckets.values().forEach(ai -> ai.set(0));

        // 2) 서비스 레이어를 통해 트랜잭션 하에서 안전하게 DB 조회
        List<Object[]> rows = userMetricService.getLastSeenHourDistribution();

        // 3) DB 결과 반영
        for (Object[] r : rows) {
            int hour = ((Number) r[0]).intValue();
            int count = ((Number) r[1]).intValue();
            if (buckets.containsKey(hour)) {
                buckets.get(hour).set(count);
            }
        }

        // 4) Gauge 업데이트를 위한 데이터 가공
        List<MultiGauge.Row<?>> gaugeRows = new ArrayList<>();
        buckets.forEach((hour, count) -> {
            gaugeRows.add(MultiGauge.Row.of(Tags.of("hour", String.format("%02d", hour)), count.get()));
        });

        peakGauge.register(gaugeRows, true);
    }
}
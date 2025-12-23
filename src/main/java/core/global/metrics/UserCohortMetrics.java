package core.global.metrics;

import core.domain.user.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class UserCohortMetrics {
    private final UserCohortService userCohortService; // 서비스 주입
    private final MeterRegistry registry;
    private MultiGauge cohortGauge;

    @PostConstruct
    public void init() {
        this.cohortGauge = MultiGauge.builder("user_cohort_retention")
                .description("30d retention by cohort week")
                .register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        refresh(); // 이제 호출 시 서비스의 @Transactional이 정상 작동함
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    public void refresh() {
        List<Object[]> rows = userCohortService.getCohortData(); // 서비스 호출
        List<MultiGauge.Row<?>> out = new ArrayList<>();

        for (Object[] r : rows) {
            String week = String.valueOf(r[0]);
            double total = ((Number) r[1]).doubleValue();
            double active = ((Number) r[2]).doubleValue();
            double rate = total > 0 ? active / total : 0.0;
            out.add(MultiGauge.Row.of(Tags.of("cohort_week", week), rate));
        }
        cohortGauge.register(out, true);
    }
}
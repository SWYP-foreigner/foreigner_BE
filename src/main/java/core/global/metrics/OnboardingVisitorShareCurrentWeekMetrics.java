package core.global.metrics;

import core.domain.user.repository.UserRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class OnboardingVisitorShareCurrentWeekMetrics {

    private final UserRepository userRepository;
    private final AtomicReference<Double> currentWeekShare = new AtomicReference<>(0.0);

    public OnboardingVisitorShareCurrentWeekMetrics(MeterRegistry registry, UserRepository userRepository) {
        this.userRepository = userRepository;
        Gauge.builder("onboarding_visitor_share_current_week", currentWeekShare, AtomicReference::get)
                .description("VISITOR / Total (current calendar week, KST)")
                .register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() { refresh(); }

    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT2M")
    public void refresh() {
        try {
            Object[] row = userRepository.visitorShareCurrentWeek();
            double total    = num(row, 0);
            double visitors = num(row, 1);
            currentWeekShare.set(total > 0 ? (visitors / total) : 0.0);
        } catch (Exception ignore) { /* noop */ }
    }

    private double num(Object[] row, int i) {
        if (row == null || row.length <= i || row[i] == null) return 0.0;
        Object v = row[i];
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0.0; }
    }
}

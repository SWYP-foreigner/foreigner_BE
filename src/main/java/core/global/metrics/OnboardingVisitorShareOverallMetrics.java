package core.global.metrics;

import core.domain.user.repository.UserRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class OnboardingVisitorShareOverallMetrics {

    private final UserRepository userRepository;
    private final AtomicReference<Double> overallShare = new AtomicReference<>(0.0);

    public OnboardingVisitorShareOverallMetrics(MeterRegistry registry, UserRepository userRepository) {
        this.userRepository = userRepository;
        Gauge.builder("onboarding_user_share_overall", overallShare, AtomicReference::get)
                .description("USER / Total (overall, KST 기준 전체 기간)")
                .register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        refresh();
    }

    @Scheduled(fixedDelayString = "PT30M", initialDelayString = "PT3M")
    public void refresh() {
        try {
            Object[] row = userRepository.visitorShareOverall();
            double total = num(row, 0);
            double users = num(row, 1);
            overallShare.set(total > 0 ? (users / total) : 0.0);
        } catch (Exception ignore) { /* noop */ }
    }

    private double num(Object[] row, int i) {
        if (row == null || row.length <= i || row[i] == null) return 0.0;
        Object v = row[i];
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return 0.0;
        }
    }
}

package core.global.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SocketDwellListener {

    private final MeterRegistry registry;

    public void onOpen(Long userId, String feature, String route) {
        if (!sampled(userId)) return;
        registry.counter("feature_dwell_sessions_total",
                Tags.of("feature", nz(feature), "route", nz(route))
        ).increment();
    }

    public void onClose(Long userId, String feature, String route, long connectedMillis) {
        if (!sampled(userId)) return;
        double seconds = Math.max(0, connectedMillis) / 1000.0;
        registry.summary("feature_dwell_seconds",
                Tags.of("feature", nz(feature), "route", nz(route))
        ).record(seconds);
    }

    private boolean sampled(Long userId) {
        double sampleRate = 1.0;
        if (sampleRate >= 1.0) return true;
        if (userId == null) return true; // uid 없으면 일단 포함
        int h = Math.abs(userId.hashCode());
        return (h % 10000) < (int)(sampleRate * 10000);
    }

    private static String nz(String s) { return (s == null || s.isBlank()) ? "unknown" : s; }
}

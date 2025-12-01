package core.global.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class NotificationMetrics {

    private final MeterRegistry registry;

    public NotificationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void mark(String channel, String step, String reason) {
        Counter.builder("notification_events_total")
                .tag("channel", channel)   // push / email / inapp ...
                .tag("step", step)         // created / sent / failed
                .tag("reason", reason)     // ok | timeout | provider_5xx ...
                .register(registry)
                .increment();
    }

    public void recordSend(String channel, String provider, long startMillis) {
        long durMs = System.currentTimeMillis() - startMillis;

        Timer.builder("notification_delivery_seconds")
                .description("푸시 알림 발송 지연 시간 (ms)")
                .tag("channel", channel)
                .tag("provider", provider)
                .publishPercentileHistogram()          // ✅ 여기서 히스토그램 켜기
                .register(registry)
                .record(durMs, TimeUnit.MILLISECONDS);
    }
}

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
                .tag("channel", channel)         // push / email / inapp ...
                .tag("step", step)               // created / sent / failed
                .tag("reason", reason)           // ok | timeout | provider_5xx 등
                .register(registry)
                .increment();
    }

    public void recordSend(String channel, String provider, long startMillis) {
        Timer.builder("notification_delivery_seconds")
                .tag("channel", channel)
                .tag("provider", provider)
                .register(registry)
                .record(System.currentTimeMillis() - startMillis,
                        TimeUnit.MILLISECONDS);
    }
}

package core.global.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class UserMetrics {

    private final AtomicInteger dau = new AtomicInteger(0);
    private final AtomicInteger mau = new AtomicInteger(0);
    private final AtomicInteger acu = new AtomicInteger(0);
    private final AtomicInteger mcu = new AtomicInteger(0);

    public UserMetrics(MeterRegistry registry) {
        Gauge.builder("app_users_dau", dau, AtomicInteger::get)
                .description("Daily Active Users").register(registry);
        Gauge.builder("app_users_mau", mau, AtomicInteger::get)
                .description("Monthly Active Users").register(registry);
        Gauge.builder("app_users_acu", acu, AtomicInteger::get)
                .description("Average Concurrent Users").register(registry);
        Gauge.builder("app_users_mcu", mcu, AtomicInteger::get)
                .description("Max Concurrent Users").register(registry);
    }

    public void update(int daily, int monthly, int avgConcurrent, int maxConcurrent) {
        dau.set(daily);
        mau.set(monthly);
        acu.set(avgConcurrent);
        mcu.set(maxConcurrent);
    }
}


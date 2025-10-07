package core.global.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class PresenceMetrics {

    private final AtomicInteger currentConnected = new AtomicInteger(0);

    public PresenceMetrics(MeterRegistry registry) {
        Gauge.builder("app_users_connected_current", currentConnected, AtomicInteger::get)
                .description("현재 접속중인 사용자 수")
                .register(registry);
    }

    public void onConnect() {
        currentConnected.incrementAndGet();
    }

    public void onDisconnect() {
        // 과도한 디크리먼트 방지 (옵션)
        int v;
        do { v = currentConnected.get();
        } while (v > 0 && !currentConnected.compareAndSet(v, v - 1));
    }

    public void setCurrentConnected(int v) { currentConnected.set(v); }
}
package core.global.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ConcurrentUsersMetrics {

    private final StringRedisTemplate redis;
    private final AtomicInteger current = new AtomicInteger(0);

    public ConcurrentUsersMetrics(MeterRegistry registry, StringRedisTemplate redis) {
        this.redis = redis;
        Gauge.builder("app_users_concurrent", current, AtomicInteger::get)
                .description("Current concurrent users (heartbeat-based)")
                .register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() { collect(); }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT15S")
    public void collect() {
        String ZKEY = "online:zset";
        long now = System.currentTimeMillis();
        long threshold = now - 120_000; // 2분 이상 지난 사용자 제거

        // 만료 정리 & 현재 카운트
        redis.opsForZSet().removeRangeByScore(ZKEY, 0, threshold);
        Long size = redis.opsForZSet().size(ZKEY);
        current.set(size != null ? size.intValue() : 0);
    }
}

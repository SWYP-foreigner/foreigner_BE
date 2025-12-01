package core.global.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class PresenceActiveUsersMetrics {

    private static final String ZKEY = "presence:active:zset";

    private final StringRedisTemplate redis;

    public PresenceActiveUsersMetrics(MeterRegistry registry, StringRedisTemplate redis) {
        this.redis = redis;

        Gauge.builder("presence_active_users", this::countActiveUsers)
                .description("TTL 이내 활동으로 간주되는 현재 활성(온라인) 유저 수")
                .register(registry);
    }

    /**
     * 현재 온라인 유저 수
     *  - score(만료시각) >= now 인 멤버만 카운트
     */
    private double countActiveUsers() {
        long now = System.currentTimeMillis();
        Long count = redis.opsForZSet()
                .count(ZKEY, (double) now, Double.POSITIVE_INFINITY);
        return count == null ? 0.0 : count.doubleValue();
    }
}

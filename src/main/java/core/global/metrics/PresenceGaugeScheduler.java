package core.global.metrics;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PresenceGaugeScheduler {

    private final StringRedisTemplate redis;
    private final PresenceMetrics presenceMetrics; // 기존 클래스 사용
    private static final String ZKEY = "presence:active:zset";

    @Scheduled(fixedDelay = 30000) // 30초마다
    public void refreshPresenceGauge() {
        long now = System.currentTimeMillis();

        // 1) 만료된 멤버 제거 (score < now)
        redis.opsForZSet().removeRangeByScore(ZKEY, Double.NEGATIVE_INFINITY, now - 1);

        // 2) 현재 접속자 수 = 만료 안 된 멤버 수
        Long count = redis.opsForZSet().count(ZKEY, now, Double.POSITIVE_INFINITY);
        int online = (count == null ? 0 : count.intValue());

        // 3) 게이지 반영
        presenceMetrics.setCurrentConnected(online);
    }
}

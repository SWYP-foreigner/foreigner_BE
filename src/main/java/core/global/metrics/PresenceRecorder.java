package core.global.metrics;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PresenceRecorder {

    private final StringRedisTemplate redis;
    private static final String ZKEY = "online:zset"; // score = epoch millis
    private static final long TTL_MILLIS = 120_000;    // 2분

    /** 인증된 요청마다 가볍게 호출 */
    public void heartbeat(Long userId) {
        if (userId == null) return;
        long now = System.currentTimeMillis();
        redis.opsForZSet().add(ZKEY, String.valueOf(userId), now);
        // (정리) 너무 잦지 않게, 5% 샘플링 or 스로틀링 원하면 여기서 조건 처리
    }
}
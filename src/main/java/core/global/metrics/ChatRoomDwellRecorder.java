package core.global.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ChatRoomDwellRecorder {

    private final MeterRegistry registry;
    private final StringRedisTemplate redis;

    // ✅ 현재 활성 세션 수
    private final AtomicInteger activeSessions = new AtomicInteger(0);

    // ✅ 방별 활성 세션 수
    private final ConcurrentHashMap<String, AtomicInteger> roomSessionCounts = new ConcurrentHashMap<>();
    // ✅ 전체 활성 방 수
    private final AtomicInteger activeRooms = new AtomicInteger(0);
    // ✅ 프로세스 시작 이후 한 번이라도 본 방 수
    private final AtomicInteger totalObservedRooms = new AtomicInteger(0);

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    // 세션 상태: (sessionId) -> 시작시각(ms), roomId
    private final ConcurrentHashMap<String, Long> startedAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> roomIdMap = new ConcurrentHashMap<>();

    private final DistributionSummary dwellSeconds;

    public ChatRoomDwellRecorder(MeterRegistry registry, StringRedisTemplate redis) {
        this.registry = registry;
        this.redis = redis;
        this.dwellSeconds = DistributionSummary.builder("chat_room_dwell_seconds")
                .description("채팅방 체류 시간(초) 분포 (전체 chat)")
                .register(registry);

        // ✅ 현재 활성 세션 수 Gauge
        Gauge.builder("chat_sessions_active", activeSessions, AtomicInteger::get)
                .description("현재 활성 채팅 세션 수 (전체)")
                .register(registry);

        // ✅ 전체 활성 방 수 Gauge
        Gauge.builder("chat_rooms_active", activeRooms, AtomicInteger::get)
                .description("현재 최소 1명 이상 접속 중인 채팅방 수")
                .register(registry);

        // ✅ 전체 관측된 방 수 Gauge (프로세스 lifetime 기준)
        Gauge.builder("chat_rooms_observed_total", totalObservedRooms, AtomicInteger::get)
                .description("프로세스 시작 이후 한 번이라도 관측된 채팅방 수")
                .register(registry);
    }

    /** 방 입장(예: STOMP SUBSCRIBE) */
    public void onEnter(String sessionId, String roomIdRaw) {
        long now = System.currentTimeMillis();
        String roomId = nz(roomIdRaw);   // ✅ 한 번 normalize 해서 전 구간에서 동일하게 사용

        startedAt.put(sessionId, now);
        roomIdMap.put(sessionId, roomId);

        // ✅ 전체 활성 세션 수 +1
        activeSessions.incrementAndGet();

        // ✅ 방별 세션 수 업데이트
        roomSessionCounts.compute(roomId, (k, v) -> {
            if (v == null) {
                // 처음 보는 방이면
                totalObservedRooms.incrementAndGet();  // 관측된 방 수 +1
                activeRooms.incrementAndGet();         // 활성 방 수 +1
                return new AtomicInteger(1);
            }
            int after = v.incrementAndGet();
            if (after == 1) {
                // 0 → 1로 바뀐 경우 활성 방 수 +1
                activeRooms.incrementAndGet();
            }
            return v;
        });
    }

    /** 방 이탈(예: DISCONNECT/UNSUBSCRIBE) */
    public void onLeave(String sessionId) {
        Long start = startedAt.remove(sessionId);
        String roomId = roomIdMap.remove(sessionId);
        if (start == null || roomId == null) return;

        // ✅ 전체 활성 세션 수 -1 (마이너스 방지하려면 Math.max로 한 번 감싸도 됨)
        activeSessions.decrementAndGet();

        // ✅ 방별 세션 수 감소
        roomSessionCounts.computeIfPresent(roomId, (k, counter) -> {
            int after = counter.decrementAndGet();
            if (after <= 0) {
                // 마지막 사람이 나간 방이면 활성 방 수 -1
                activeRooms.decrementAndGet();
            }
            return counter;
        });

        long durMs = Math.max(0, System.currentTimeMillis() - start);
        double seconds = durMs / 1000.0;

        // 분포 기록(태그 없음, 전체 chat 단일 시계열)
        dwellSeconds.record(seconds);

        // Top-N: 일별 roomId 누적 체류초
        String zkey = "chat:dwell:" + DAY_FMT.format(LocalDate.now());
        redis.opsForZSet().incrementScore(zkey, roomId, seconds);
        // 세션 수도 함께 보려면 유지
        redis.opsForZSet().incrementScore(zkey + ":sessions", roomId, 1.0);
    }

    private static String nz(String s) {
        return (s == null || s.isBlank()) ? "unknown" : s;
    }
}

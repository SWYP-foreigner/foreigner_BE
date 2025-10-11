package core.global.metrics;

import core.domain.user.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@Slf4j
@Component
public class UserInactiveMetrics {

    private final UserRepository userRepository;
    private final MultiGauge inactiveGauge;

    // MultiGauge 빈 등록
    public UserInactiveMetrics(MeterRegistry registry, UserRepository userRepository) {
        this.userRepository = userRepository;
        this.inactiveGauge = MultiGauge.builder("user_inactivity_hours")
                .description("Number of users by inactivity buckets (hours since lastSeenAt)")
                .register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        safeCollect();
    }

    /** 2분마다 갱신 */
    @Scheduled(fixedDelayString = "PT2M")
    public void scheduled() {
        safeCollect();
    }

    private void safeCollect() {
        try {
            Object raw = userRepository.countInactiveBuckets();

            // 1) raw → 1행 Object[]로 정규화
            Object[] row;

            if (raw instanceof Object[] arr) {
                // 케이스 A) Object[][] (배열 안에 배열) → 첫 요소가 배열이면 한 번 풀기
                if (arr.length == 1 && arr[0] instanceof Object[] inner) {
                    row = inner;
                } else if (arr.length > 0 && !(arr[0] instanceof Object)) {
                    // 이 케이스는 거의 없음. 안전장치
                    row = arr;
                } else {
                    // 대부분의 정상 케이스: 이미 1행 Object[]
                    row = arr;
                }
            } else if (raw instanceof java.util.Collection<?> col) {
                if (col.isEmpty()) {
                    log.warn("countInactiveBuckets() returned empty collection");
                    return;
                }
                Object first = col.iterator().next();
                if (!(first instanceof Object[] inner)) {
                    log.warn("Unexpected element type: {}", first == null ? "null" : first.getClass());
                    return;
                }
                row = inner;
            } else {
                log.warn("countInactiveBuckets() returned unexpected: {}", raw == null ? "null" : raw.getClass());
                return;
            }

            // 2) 숫자화 (null→0, BigInteger/Long/Integer 등 모두 처리)
            double[] nums = new double[row.length];
            for (int i = 0; i < row.length; i++) {
                Object v = row[i];
                if (v == null) {
                    nums[i] = 0d;
                } else if (v instanceof Number n) {
                    nums[i] = n.doubleValue();
                } else if (v instanceof Object[] nested && nested.length > 0 && nested[0] instanceof Number n0) {
                    // 드문 케이스: 컬럼 값이 다시 배열에 들어간 형태
                    nums[i] = n0.doubleValue();
                } else {
                    // 문자열로 오는 경우 마지막 방어
                    nums[i] = Double.parseDouble(String.valueOf(v));
                }
            }

            // 3) MultiGauge 등록/치환
            inactiveGauge.register(
                    java.util.List.of(
                            MultiGauge.Row.of(Tags.of("le", "24",   "order", "024"), nums[0]),
                            MultiGauge.Row.of(Tags.of("le", "72",   "order", "072"), nums[1]),
                            MultiGauge.Row.of(Tags.of("le", "168",  "order", "168"), nums[2]),
                            MultiGauge.Row.of(Tags.of("le", "336",  "order", "336"), nums[3]),
                            MultiGauge.Row.of(Tags.of("le", "720",  "order", "720"), nums[4]),
                            MultiGauge.Row.of(Tags.of("le", "+Inf", "order", "999"), nums[5])
                    ),
                    true
            );


            log.debug("user_inactivity_hours updated: {}", java.util.Arrays.toString(nums));
        } catch (Exception e) {
            log.error("Failed to update user_inactivity_hours", e);
        }
    }

}
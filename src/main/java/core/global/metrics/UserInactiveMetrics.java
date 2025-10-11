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

            // raw 가 Object[] 인지, List<Object[]> 인지 모두 수용
            Object[] row;
            if (raw instanceof Object[] arr) {
                row = arr;
            } else if (raw instanceof Collection<?> col && !col.isEmpty()) {
                Object first = col.iterator().next();
                if (!(first instanceof Object[] f)) {
                    log.warn("Unexpected element type from countInactiveBuckets(): {}", first == null ? "null" : first.getClass());
                    return;
                }
                row = f;
            } else {
                log.warn("countInactiveBuckets() returned empty/unknown: {}", raw);
                return;
            }

            // 숫자 배열로 변환 (null → 0)
            double[] nums = new double[row.length];
            for (int i = 0; i < row.length; i++) {
                Object v = row[i];
                if (v == null) {
                    nums[i] = 0d;
                } else if (v instanceof Number n) {
                    nums[i] = n.doubleValue();
                } else {
                    // 드물게 문자열로 올 때 방어
                    nums[i] = Double.parseDouble(String.valueOf(v));
                }
            }

            inactiveGauge.register(
                    List.of(
                            MultiGauge.Row.of(Tags.of("le", "24"),   nums[0]),
                            MultiGauge.Row.of(Tags.of("le", "72"),   nums[1]),
                            MultiGauge.Row.of(Tags.of("le", "168"),  nums[2]),
                            MultiGauge.Row.of(Tags.of("le", "336"),  nums[3]),
                            MultiGauge.Row.of(Tags.of("le", "720"),  nums[4]),
                            MultiGauge.Row.of(Tags.of("le", "+Inf"), nums[5])
                    ),
                    true // replace
            );

            log.debug("user_inactivity_hours updated: {}", Arrays.toString(nums));
        } catch (Exception e) {
            // 어떤 예외가 와도 스케줄러는 계속 돌게
            log.error("Failed to update user_inactivity_hours", e);
        }
    }
}
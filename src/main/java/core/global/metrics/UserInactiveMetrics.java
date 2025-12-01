package core.global.metrics;

import core.domain.user.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class UserInactiveMetrics {

    private final UserRepository userRepository;
    private final MultiGauge inactiveGauge;

    public UserInactiveMetrics(MeterRegistry registry, UserRepository userRepository) {
        this.userRepository = userRepository;
        this.inactiveGauge = MultiGauge.builder("user_inactivity_hours")
                .description("Number of users by inactivity buckets (hours since lastSeenAt)")
                .register(registry);

        // ⬇️ 스케줄 대신, 스크랩 시점에 동적으로 값을 계산하는 Row 등록
        registerLazyRows();
    }

    /**
     * 애플리케이션 준비 완료 시 한 번 로그만 찍어둡니다.
     * (실제 메트릭 값 계산은 Prometheus 스크랩 시점에 이루어집니다.)
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("user_inactivity_hours MultiGauge registered (lazy evaluation using lastSeenAt).");
    }

    /**
     * 과거 safeCollect()는 DB 값을 읽어와 MultiGauge를 갱신하는 역할이었는데,
     * 이제는 "버킷 배열을 계산해서 돌려주는" 내부 유틸 메서드로 사용합니다.
     */
    private void safeCollect() {
        // 이 메서드는 더 이상 직접 호출되지 않도록 두거나,
        // 필요하다면 아래 computeBuckets() 를 호출하는 식으로 활용할 수 있습니다.
        // (호환성을 위해 이름만 유지)
        computeBuckets();
    }

    /**
     * MultiGauge에 "지연 평가(lazy evaluation)" Row 등록
     * - 각 Row는 this(UserInactiveMetrics 인스턴스)와, 버킷 인덱스를 받아서
     *   Prometheus 스크랩 시마다 DB에서 lastSeenAt 기반 버킷 카운트를 계산합니다.
     */
    private void registerLazyRows() {
        inactiveGauge.register(
                java.util.List.of(
                        MultiGauge.Row.of(Tags.of("le", "24",   "order", "024"), this, m -> m.getBucketValue(0)),
                        MultiGauge.Row.of(Tags.of("le", "72",   "order", "072"), this, m -> m.getBucketValue(1)),
                        MultiGauge.Row.of(Tags.of("le", "168",  "order", "168"), this, m -> m.getBucketValue(2)),
                        MultiGauge.Row.of(Tags.of("le", "336",  "order", "336"), this, m -> m.getBucketValue(3)),
                        MultiGauge.Row.of(Tags.of("le", "720",  "order", "720"), this, m -> m.getBucketValue(4)),
                        MultiGauge.Row.of(Tags.of("le", "+Inf", "order", "999"), this, m -> m.getBucketValue(5))
                )
        );
    }

    /**
     * 특정 버킷 인덱스의 값을 계산합니다.
     * - 내부에서 매번 DB를 조회하여, lastSeenAt 기준으로 각 버킷 카운트를 계산합니다.
     */
    private double getBucketValue(int index) {
        try {
            double[] nums = computeBuckets();
            if (index < 0 || index >= nums.length) {
                return 0d;
            }
            return nums[index];
        } catch (Exception e) {
            log.error("Failed to compute user_inactivity_hours bucket index={}", index, e);
            // 에러 시 0 혹은 Double.NaN 중 선택 가능. 보통 0으로 두는 편이 무난합니다.
            return 0d;
        }
    }

    /**
     * DB의 countInactiveBuckets() 결과를 읽어서
     * [24h, 72h, 168h, 336h, 720h, +Inf] 순서의 double[] 로 변환합니다.
     *
     * ⚠️ 전제: Repository 단에서 lastSeenAt 기준으로 버킷을 계산하는 쿼리를 사용하고 있어야 합니다.
     */
    private double[] computeBuckets() {
        Object raw = userRepository.countInactiveBuckets();

        Object[] row;

        if (raw instanceof Object[] arr) {
            if (arr.length == 1 && arr[0] instanceof Object[] inner) {
                row = inner;
            } else {
                row = arr;
            }
        } else if (raw instanceof java.util.Collection<?> col) {
            if (col.isEmpty()) {
                log.warn("countInactiveBuckets() returned empty collection");
                return new double[]{0, 0, 0, 0, 0, 0};
            }
            Object first = col.iterator().next();
            if (!(first instanceof Object[] inner)) {
                log.warn("Unexpected element type: {}", first == null ? "null" : first.getClass());
                return new double[]{0, 0, 0, 0, 0, 0};
            }
            row = inner;
        } else {
            log.warn("countInactiveBuckets() returned unexpected: {}", raw == null ? "null" : raw.getClass());
            return new double[]{0, 0, 0, 0, 0, 0};
        }

        double[] nums = new double[row.length];
        for (int i = 0; i < row.length; i++) {
            Object v = row[i];
            if (v == null) {
                nums[i] = 0d;
            } else if (v instanceof Number n) {
                nums[i] = n.doubleValue();
            } else if (v instanceof Object[] nested && nested.length > 0 && nested[0] instanceof Number n0) {
                nums[i] = n0.doubleValue();
            } else {
                nums[i] = Double.parseDouble(String.valueOf(v));
            }
        }

        // 최소 6개는 맞춰줍니다.
        if (nums.length < 6) {
            double[] fixed = new double[6];
            System.arraycopy(nums, 0, fixed, 0, nums.length);
            return fixed;
        }

        log.debug("user_inactivity_hours (lazy) buckets: {}", java.util.Arrays.toString(nums));
        return nums;
    }

}

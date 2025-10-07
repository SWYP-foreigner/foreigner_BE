package core.global.metrics;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;

@Component
@RequiredArgsConstructor
public class UserStatsScheduler {

    private final StringRedisTemplate redis;
    private final UserMetrics userMetrics;

    @Scheduled(cron = "0 */5 * * * *") // 5분마다 업데이트(대시보드 신선도)
    public void refreshUserMetrics() {
        String dayKey = "hll:active:day:" + LocalDate.now();
        String monthKey = "hll:active:month:" + YearMonth.now();

        Long dau = redis.opsForHyperLogLog().size(dayKey);
        Long mau = redis.opsForHyperLogLog().size(monthKey);

        // 3-2 전략: ACU/MCU는 PromQL로 계산 권장 → 앱에서는 0 또는 직전값 유지
        int acu = 0; // computeACU(); // Prometheus에서 avg_over_time 사용 권장
        int mcu = 0; // computeMCU(); // Prometheus에서 max_over_time 사용 권장

        userMetrics.update(
                dau != null ? dau.intValue() : 0,
                mau != null ? mau.intValue() : 0,
                acu, mcu
        );
    }
}

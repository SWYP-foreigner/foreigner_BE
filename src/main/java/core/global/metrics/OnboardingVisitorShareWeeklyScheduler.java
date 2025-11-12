package core.global.metrics;

import core.domain.user.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class OnboardingVisitorShareWeeklyScheduler {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter WEEK_LABEL = DateTimeFormatter.ofPattern("yyyy-MM-dd"); // 주 시작일 라벨
    private final UserRepository userRepository;
    private final MeterRegistry registry;
    private MultiGauge weeklyGauge;

    @Scheduled(fixedDelayString = "PT30M", initialDelayString = "PT1M")
    public void refresh() {
        if (weeklyGauge == null) {
            weeklyGauge = MultiGauge.builder("onboarding_user_share_weekly")
                    .description("Weekly USER / Total (calendar week, KST)")
                    .register(registry);
        }

        // KST 기준 이번 주 시작(월요일 00:00)
        LocalDate kstToday = LocalDate.now(KST);
        LocalDate thisWeekStart = kstToday.with(java.time.DayOfWeek.MONDAY);

        // 최근 12주 범위: 11주 전 시작 ~ 다음 주 시작
        LocalDate fromWeek = thisWeekStart.minusWeeks(11);
        LocalDate toWeek = thisWeekStart.plusWeeks(1);

        Instant fromUtc = fromWeek.atStartOfDay(KST).toInstant();
        Instant toUtc = toWeek.atStartOfDay(KST).toInstant();

        List<Object[]> rows = userRepository.visitorShareWeekly(fromUtc, toUtc);
        List<MultiGauge.Row<?>> out = new ArrayList<>();

        for (Object[] r : rows) {
            // week_kst is date (주 시작일)
            String weekStartStr = String.valueOf(r[0]); // e.g., 2025-01-13
            LocalDate weekStart = LocalDate.parse(weekStartStr);
            String label = weekStart.format(WEEK_LABEL); // "yyyy-MM-dd"

            double total = asDouble(r[1]);
            double users = asDouble(r[2]);
            double share = total > 0 ? (users / total) : 0.0;

            out.add(MultiGauge.Row.of(Tags.of("week_start", label), share));
        }
        weeklyGauge.register(out, true);
    }

    private double asDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return 0.0;
        }
    }
}
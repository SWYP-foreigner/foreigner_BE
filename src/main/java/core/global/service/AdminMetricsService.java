package core.global.service;

import core.domain.chat.repository.ChatMessageRepository;
import core.domain.user.repository.UserRepository;
import core.global.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminMetricsService {

    private final UserRepository userRepository;
    private final ChatMessageRepository chatMessageRepository;

    @Transactional(readOnly = true)
    public AdminMetricsDto getDashboardMetrics(int signupDays, int validJoinDays, int validActiveDays) {

        InactiveUserStatsDto userStats = fetchInactiveUserStats();
        UserActivityBucketsDto activityBuckets = fetchUserActivityBuckets();
        List<WeeklyCohortDto> weeklyCohorts = fetchWeeklyCohorts();

        AdvancedMetricsDto advancedMetrics = fetchAdvancedMetrics(signupDays, validJoinDays, validActiveDays);

        return new AdminMetricsDto(userStats, activityBuckets, weeklyCohorts, advancedMetrics);
    }

    private AdvancedMetricsDto fetchAdvancedMetrics(int signupDays, int validJoinDays, int validActiveDays) {
        long recentSignups = userRepository.countRecentSignups(signupDays);

        long effectiveActive = userRepository.countEffectiveActiveUsers(validJoinDays, validActiveDays);

        long recentActiveExisting = userRepository.countRecentActiveExistingUsers(validJoinDays);

        long joined7DaysAgo = userRepository.countUsersJoined7DaysAgo();
        long retained7Days = userRepository.countUsersJoined7DaysAgoAndActiveToday();
        double d7Retention = (joined7DaysAgo == 0) ? 0.0 : ((double) retained7Days / joined7DaysAgo) * 100.0;

        long msgCount7Days = chatMessageRepository.countMessagesLast7Days();
        long activeUsers7Days = userRepository.countActiveUsersLast7Days();
        double avgMsg = (activeUsers7Days == 0) ? 0.0 : (double) msgCount7Days / activeUsers7Days;

        // todo: 인기 기능 (로그 테이블이 없으므로 예시 로직 or 더미 데이터)
        String topFeatureName = "실시간 번역";
        double topFeatureUsageRate = 34.1;

        return new AdvancedMetricsDto(
                recentSignups,
                effectiveActive,
                recentActiveExisting,
                Math.round(d7Retention * 10) / 10.0,
                Math.round(avgMsg * 10) / 10.0,
                topFeatureName,
                topFeatureUsageRate
        );
    }

    private InactiveUserStatsDto fetchInactiveUserStats() {
        List<Object[]> results = userRepository.countInactiveStatsAndTotal();

        if (results.isEmpty() || results.get(0) == null) {
            return new InactiveUserStatsDto(0, 0, 0, 0);
        }
        Object[] result = results.get(0);

        long inactive30 = safeToLong(result[0]);
        long total = safeToLong(result[1]);
        long inactive7 = safeToLong(result[2]);
        long inactive3 = safeToLong(result[3]);

        return new InactiveUserStatsDto(total, inactive30, inactive7, inactive3);
    }

    private UserActivityBucketsDto fetchUserActivityBuckets() {
        List<Object[]> results = userRepository.countInactiveBuckets();

        if (results.isEmpty() || results.get(0) == null) {
            return new UserActivityBucketsDto(0, 0, 0);
        }
        Object[] result = results.get(0);

        long h0_24 = safeToLong(result[0]);
        long h24_72 = safeToLong(result[1]);
        long h72_168 = safeToLong(result[2]);

        return new UserActivityBucketsDto(h0_24, h24_72, h72_168);
    }

    private List<WeeklyCohortDto> fetchWeeklyCohorts() {
        return userRepository.cohortRetention30d().stream()
                .map(row -> {
                    LocalDate week = (row[0] != null) ? ((Date) row[0]).toLocalDate() : null;
                    long total = ((Number) row[1]).longValue();
                    long active = ((Number) row[2]).longValue();
                    return new WeeklyCohortDto(week, total, active);
                })
                .collect(Collectors.toList());
    }

    private long safeToLong(Object obj) {
        if (obj == null) {
            return 0L;
        }
        return Long.parseLong(String.valueOf(obj));
    }
}
package core.global.service;

import core.domain.user.repository.UserRepository;
import core.global.dto.AdminMetricsDto;
import core.global.dto.InactiveUserStatsDto;
import core.global.dto.UserActivityBucketsDto;
import core.global.dto.WeeklyCohortDto;
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

    @Transactional(readOnly = true)
    public AdminMetricsDto getDashboardMetrics() {
        // 각 통계 조회 메서드 호출
        InactiveUserStatsDto userStats = fetchInactiveUserStats();
        UserActivityBucketsDto activityBuckets = fetchUserActivityBuckets();
        List<WeeklyCohortDto> weeklyCohorts = fetchWeeklyCohorts();

        return new AdminMetricsDto(userStats, activityBuckets, weeklyCohorts);
    }

    // 네이티브 쿼리 결과를 DTO로 파싱하는 private 메서드들
    private InactiveUserStatsDto fetchInactiveUserStats() {
        List<Object[]> results = userRepository.countInactive30dAndTotal();
        if (results.isEmpty() || results.get(0) == null) {
            return new InactiveUserStatsDto(0, 0);
        }
        Object[] result = results.get(0);

        long inactive = safeToLong(result[0]);
        long total = safeToLong(result[1]);

        return new InactiveUserStatsDto(total, inactive);
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
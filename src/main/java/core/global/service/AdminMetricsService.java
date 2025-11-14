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
        long inactive = ((Number) result[0]).longValue();
        long total = ((Number) result[1]).longValue();
        return new InactiveUserStatsDto(total, inactive);
    }

    private UserActivityBucketsDto fetchUserActivityBuckets() {
        // 1. 반환 타입을 List<Object[]>로 받습니다.
        List<Object[]> results = userRepository.countInactiveBuckets();

        // 2. 리스트가 비어있는지 확인하고, 첫 번째 요소를 꺼내서 사용합니다.
        if (results.isEmpty() || results.get(0) == null) {
            return new UserActivityBucketsDto(0, 0, 0);
        }
        Object[] result = results.get(0);

        // 3. 각 요소를 숫자로 변환합니다. (이 부분은 이전과 동일)
        long h0_24 = ((Number) result[0]).longValue();
        long h24_72 = ((Number) result[1]).longValue();
        long h72_168 = ((Number) result[2]).longValue();
        return new UserActivityBucketsDto(h0_24, h24_72, h72_168);
    }

    private List<WeeklyCohortDto> fetchWeeklyCohorts() {
        return userRepository.cohortRetention30d().stream()
                .map(row -> {
                    LocalDate week = ((Date) row[0]).toLocalDate();
                    long total = ((Number) row[1]).longValue();
                    long active = ((Number) row[2]).longValue();
                    return new WeeklyCohortDto(week, total, active);
                })
                .collect(Collectors.toList());
    }
}
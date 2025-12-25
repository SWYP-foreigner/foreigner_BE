package core.global.service;

import core.domain.chat.repository.ChatMessageRepository;
import core.domain.user.dto.CountryRetentionDto;
import core.domain.user.dto.StringCountDto;
import core.domain.user.repository.UserRepository;
import core.global.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminMetricsService {

    private final UserRepository userRepository;
    private final ChatMessageRepository chatMessageRepository;

    @Transactional(readOnly = true)
    public AdminMetricsDto getDashboardMetrics(
            LocalDate joinStartDate,
            LocalDate joinEndDate,
            int validJoinDays,
            int validActiveDays
    ) {
        InactiveUserStatsDto userStats = fetchInactiveUserStats();
        UserActivityBucketsDto activityBuckets = fetchUserActivityBuckets();
        List<WeeklyCohortDto> weeklyCohorts = fetchWeeklyCohorts();
        AdvancedMetricsDto advancedMetrics = fetchAdvancedMetrics(joinStartDate, joinEndDate, validJoinDays, validActiveDays);

        List<StringCountDto> countryStats = userRepository.countUsersByCountry();
        List<StringCountDto> languageStats = userRepository.countUsersByLanguage();
        List<CountryRetentionDto> countryRetentions = fetchCountryRetentions(joinStartDate, joinEndDate);

        return new AdminMetricsDto(
                userStats,
                activityBuckets,
                weeklyCohorts,
                advancedMetrics,
                countryStats,
                languageStats,
                countryRetentions
        );
    }

    private List<CountryRetentionDto> fetchCountryRetentions(LocalDate startDate, LocalDate endDate) {
        ZoneId kstZone = ZoneId.of("Asia/Seoul");

        Instant startInstant = startDate.atStartOfDay(kstZone).toInstant();
        Instant endInstant = endDate.atTime(LocalTime.MAX).atZone(kstZone).toInstant();

        Instant activeThreshold = Instant.now().minus(24, java.time.temporal.ChronoUnit.HOURS);

        List<Object[]> results = userRepository.aggregateCountryRetention(startInstant, endInstant, activeThreshold);

        return results.stream()
                .map(row -> {
                    String country = (String) row[0];
                    long total = ((Number) row[1]).longValue();
                    long retained = ((Number) row[2]).longValue();

                    double rate = (total == 0) ? 0.0 : ((double) retained / total) * 100.0;

                    return new CountryRetentionDto(
                            country,
                            total,
                            retained,
                            Math.round(rate * 10) / 10.0
                    );
                })
                .collect(Collectors.toList());
    }

    private AdvancedMetricsDto fetchAdvancedMetrics(
            LocalDate startDate,
            LocalDate endDate,
            int validJoinDays,
            int validActiveDays
    ) {
        ZoneId kstZone = ZoneId.of("Asia/Seoul");
        Instant startInstant = startDate.atStartOfDay(kstZone).toInstant();
        Instant endInstant = endDate.atTime(LocalTime.MAX).atZone(kstZone).toInstant();

        long periodSignups = userRepository.countUsersJoinedInPeriod(startInstant, endInstant);
        long retainedUsers = userRepository.countUsersJoinedInPeriodAndActiveToday(startInstant, endInstant);

        double retentionRate = (periodSignups == 0) ? 0.0 : ((double) retainedUsers / periodSignups) * 100.0;
        String periodLabel = startDate.format(DateTimeFormatter.ofPattern("MM.dd")) + " ~ " + endDate.format(DateTimeFormatter.ofPattern("MM.dd"));

        long effectiveActive = userRepository.countEffectiveActiveUsers(validJoinDays, validActiveDays);
        long recentActiveExisting = userRepository.countRecentActiveExistingUsers(validJoinDays);

        long msgCount7Days = chatMessageRepository.countMessagesLast7Days();
        long activeUsers7Days = userRepository.countActiveUsersLast7Days();
        double avgMsg = (activeUsers7Days == 0) ? 0.0 : (double) msgCount7Days / activeUsers7Days;

        String topFeatureName = "실시간 번역";
        double topFeatureUsageRate = 34.1;

        return new AdvancedMetricsDto(
                periodSignups,
                effectiveActive,
                recentActiveExisting,
                Math.round(retentionRate * 10) / 10.0,
                periodLabel,
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
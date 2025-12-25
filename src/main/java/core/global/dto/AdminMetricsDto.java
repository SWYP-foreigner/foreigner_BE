package core.global.dto;

import core.domain.user.dto.StringCountDto;

import java.util.List;

public record AdminMetricsDto(
        InactiveUserStatsDto userStats,
        UserActivityBucketsDto activityBuckets,
        List<WeeklyCohortDto> weeklyCohorts,
        AdvancedMetricsDto advancedMetrics,
        List<StringCountDto> countryStats,
        List<StringCountDto> languageStats
) {}

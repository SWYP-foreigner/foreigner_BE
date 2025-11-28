package core.global.dto;

import java.util.List;

public record AdminMetricsDto(
        InactiveUserStatsDto userStats,
        UserActivityBucketsDto activityBuckets,
        List<WeeklyCohortDto> weeklyCohorts,
        AdvancedMetricsDto advancedMetrics
) {}

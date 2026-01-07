package core.domain.admin.dto;

public record AdvancedMetricsDto(
        long periodSignupsCount,
        long effectiveActiveUsersCount,
        long recentActiveExistingUsersCount,
        double retentionRate,
        String periodLabel,
        double avgMessagesPerUser7Days,
        String topFeatureName,
        double topFeatureUsageRate
) {}

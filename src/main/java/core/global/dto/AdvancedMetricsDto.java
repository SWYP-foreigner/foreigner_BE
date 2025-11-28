package core.global.dto;

public record AdvancedMetricsDto(
        long recentSignupsCount,
        long effectiveActiveUsersCount,
        long recentActiveExistingUsersCount,
        double d7RetentionRate,
        double avgMessagesPerUser7Days,
        String topFeatureName,
        double topFeatureUsageRate
) {}

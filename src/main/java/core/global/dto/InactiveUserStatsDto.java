package core.global.dto;

public record InactiveUserStatsDto(
        long totalUsers,
        long inactiveLast30Days
) {}

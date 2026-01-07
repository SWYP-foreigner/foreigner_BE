package core.domain.admin.dto;

public record GhostUserStatsDto(
        long totalSignups,
        long ghostCount,
        double ghostRate
) {}

package core.global.dto;

public record GhostUserStatsDto(
        long totalSignups,
        long ghostCount,
        double ghostRate
) {}

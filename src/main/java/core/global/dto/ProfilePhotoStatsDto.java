package core.global.dto;

public record ProfilePhotoStatsDto(
        long totalSignups,
        long customPhotoCount,
        double ratio
) {}

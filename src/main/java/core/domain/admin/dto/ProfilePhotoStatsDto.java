package core.domain.admin.dto;

public record ProfilePhotoStatsDto(
        long totalSignups,
        long customPhotoCount,
        double ratio
) {}

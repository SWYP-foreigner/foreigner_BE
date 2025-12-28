package core.domain.admin.dto;

public record FirstMessageTimeDto(
        long within1Min,
        long within1Hour,
        long after1Hour,
        long never,
        long total,
        double quickRatio
) {}

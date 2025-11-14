package core.global.dto;

import java.time.LocalDate;

public record WeeklyCohortDto(
        LocalDate cohortWeek,
        long totalSignups,
        long activeInLast30Days
) {}

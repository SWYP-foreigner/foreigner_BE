package core.global.dto;

import java.util.Map;

public record DemographicsDto(
        Map<String, Long> genderCounts,
        Map<String, Long> ageGroupCounts,
        long totalUsers
) {}

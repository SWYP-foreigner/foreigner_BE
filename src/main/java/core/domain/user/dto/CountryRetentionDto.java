package core.domain.user.dto;

public record CountryRetentionDto(
        String countryName,
        Long totalSignups,
        Long retainedUsers,
        Double retentionRate
) {}

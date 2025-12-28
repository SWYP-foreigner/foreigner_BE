package core.domain.admin.dto;

public record UserActivityBucketsDto(
        long activeLast24h,
        long active24hTo72h,
        long active72hTo168h
) {}

package core.domain.admin.dto;

public record TargetPushRequest(
        String targetCountry,
        String title,
        String body
) {}

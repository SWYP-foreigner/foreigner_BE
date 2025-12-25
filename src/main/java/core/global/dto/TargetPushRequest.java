package core.global.dto;

public record TargetPushRequest(
        String targetCountry,
        String title,
        String body
) {}

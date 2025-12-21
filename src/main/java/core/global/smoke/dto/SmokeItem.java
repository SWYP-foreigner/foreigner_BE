package core.global.smoke.dto;

public record SmokeItem(
        String name,
        String method,
        String path,
        Integer status,
        long elapsedMs,
        boolean ok,
        String error
) {}
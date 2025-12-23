package core.global.smoke;
import java.util.List;

public record SmokeResult(
        String mode,
        boolean ok,
        int total,
        int passed,
        int failed,
        long durationMs,
        List<SmokeItem> items
) {}



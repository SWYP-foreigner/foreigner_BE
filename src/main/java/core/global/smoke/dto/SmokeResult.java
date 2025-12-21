package core.global.smoke.dto;
import java.util.List;

public record SmokeResult(
        String mode,
        boolean success,
        int totalCount,
        int passedCount,
        int failedCount,
        long elapsedTimeMs,
        List<SmokeItem> passedItems,
        List<SmokeItem> failedItems
) {
}



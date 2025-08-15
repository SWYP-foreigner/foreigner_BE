package core.domain.comment.dto;

import java.time.Instant;
import java.util.List;

public record CursorPage<T>(
        List<T> items,
        boolean hasNext,
        Instant nextCursorCreatedAt,
        Long nextCursorId,
        Long nextCursorLikeCount
) {
}

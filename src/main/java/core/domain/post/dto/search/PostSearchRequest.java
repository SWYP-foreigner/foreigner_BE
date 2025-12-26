package core.domain.post.dto.search;

import java.time.Instant;
import java.util.List;

public record PostSearchRequest(
        String q,
        Long userId,
        Long boardId,
        List<Long> blockedIds,
        Double afterScore,
        Instant afterTime,
        Long afterId,
        int limit
) {}
package core.domain.bookmark.dto;

import java.util.List;

public record BookmarkCursorPageResponse<T>(
        List<T> items,
        Long nextCursorId,
        boolean hasNext
) {
}

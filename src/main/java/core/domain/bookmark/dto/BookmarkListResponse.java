package core.domain.bookmark.dto;

import java.util.List;

public record BookmarkListResponse(
        Long bookmarkId,
        String authorName,
        String content,
        Long likeCount,
        Long commentCount,
        Long checkCount,
        Boolean isMarked,
        String userImage,
        List<String> postImages
) {
}

package core.domain.comment.dto;

import java.util.List;

public record UserCommentsSliceResponse(
        List<UserCommentItem> items,
        Long nextCursor,
        boolean hasNext
) {
}

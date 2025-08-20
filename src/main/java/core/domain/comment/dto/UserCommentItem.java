package core.domain.comment.dto;

import java.time.Instant;

public record UserCommentItem(
        Long commentId,
        String postContent,
        String commentContent,
        Instant createdAt
) {
}

package core.domain.comment.dto;

import core.domain.comment.entity.Comment;
import io.micrometer.common.lang.Nullable;

import java.time.Instant;

public record CommentResponse(
        String username,
        String content,
        Long likeCount,
        Instant createdAt,
        String userImage,
        Boolean deleted
) {
    public static CommentResponse from(Comment c,
                                       long likeCount,
                                       @Nullable String userImageUrl) {
        if (c.isDeleted()) {
            return new CommentResponse(
                    null,
                    "삭제된 댓글입니다.",
                    0L,
                    c.getCreatedAt(),
                    null,
                    true
            );
        }
        return new CommentResponse(
                (c.getAuthor() != null) ? c.getAuthor().getName() : null,
                c.getContent(),
                likeCount,
                c.getCreatedAt(),
                userImageUrl,
                false
        );
    }
}

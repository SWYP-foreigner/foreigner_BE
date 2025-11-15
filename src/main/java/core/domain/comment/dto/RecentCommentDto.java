package core.domain.comment.dto;

import core.domain.comment.entity.Comment;

import java.time.Instant;

public record RecentCommentDto(
        Long commentId,
        Long postId,
        String content,
        Instant createdAt
) {
    public static RecentCommentDto from(Comment comment) {
        String truncatedContent = comment.getContent().length() > 50 ?
                comment.getContent().substring(0, 50) + "..." : comment.getContent();
        return new RecentCommentDto(comment.getId(), comment.getPost().getId(), truncatedContent, comment.getCreatedAt());
    }
}

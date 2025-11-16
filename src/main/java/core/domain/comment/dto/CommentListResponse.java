package core.domain.comment.dto;

import core.domain.comment.entity.Comment;

import java.time.Instant;

public record CommentListResponse(
        Long commentId,
        Long postId,
        String authorName,
        String authorEmail,
        String content,
        Instant createdAt,
        Long reportCount,
        boolean isDeleted
) {
    public static CommentListResponse from(Comment comment, Long reportCount) {
        String truncatedContent = comment.getContent().length() > 30 ?
                comment.getContent().substring(0, 30) + "..." : comment.getContent();
        return new CommentListResponse(
                comment.getId(),
                comment.getPost().getId(),
                comment.getAuthor().getFirstName()+" "+comment.getAuthor().getLastName(),
                comment.getAuthor().getEmail(),
                truncatedContent,
                comment.getCreatedAt(),
                reportCount,
                comment.isDeleted()
        );
    }
}

package core.domain.post.dto;

import core.domain.post.entity.Post;

import java.time.Instant;

public record PostListResponse(
        Long postId,
        String authorName,
        String authorEmail,
        String content,
        Instant createdAt,
        Long reportCount
) {
    public static PostListResponse from(Post post, Long reportCount) {
        String truncatedContent = post.getContent().length() > 50 ?
                post.getContent().substring(0, 50) + "..." : post.getContent();
        return new PostListResponse(
                post.getId(),
                post.getAuthor().getName(),
                post.getAuthor().getEmail(),
                truncatedContent,
                post.getCreatedAt(),
                reportCount
        );
    }
}

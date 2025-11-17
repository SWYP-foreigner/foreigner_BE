package core.domain.post.dto.admin;

import core.domain.post.entity.Post;

import java.time.Instant;

public record PostListForAdminResponse(
        Long postId,
        String authorName,
        String authorEmail,
        String content,
        Instant createdAt,
        Long reportCount
) {
    public static PostListForAdminResponse from(Post post, Long reportCount) {
        String truncatedContent = post.getContent().length() > 50 ?
                post.getContent().substring(0, 50) + "..." : post.getContent();
        return new PostListForAdminResponse(
                post.getId(),
                post.getAuthor().getFirstName()+" "+post.getAuthor().getLastName(),
                post.getAuthor().getEmail(),
                truncatedContent,
                post.getCreatedAt(),
                reportCount
        );
    }
}

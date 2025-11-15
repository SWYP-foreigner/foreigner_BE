package core.domain.post.dto.admin;

import core.domain.post.entity.Post;

import java.time.Instant;

public record RecentPostDto(
        Long postId,
        String content,
        Instant createdAt
) {
    public static RecentPostDto from(Post post) {
        String truncatedContent = post.getContent().length() > 50 ?
                post.getContent().substring(0, 50) + "..." : post.getContent();
        return new RecentPostDto(post.getId(), truncatedContent, post.getCreatedAt());
    }
}

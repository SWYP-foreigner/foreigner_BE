package core.domain.post.dto;

import java.time.Instant;

public record UserPostResponse(
        String content,
        Instant createdAt,
        Long likeCount,
        Long commentCount,
        Long viewCount,
        String imageUrl
) {
}

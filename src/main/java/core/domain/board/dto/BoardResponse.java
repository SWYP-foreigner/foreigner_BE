package core.domain.board.dto;

import java.time.Instant;

public record BoardResponse(
        Long postId,
        String contentPreview,
        String userName,
        Instant createdAt,
        Long likeCount,
        Long commentCount,
        Long viewCount,
        String userImageUrl,
        String contentImageUrl,
        Long score
) {
}

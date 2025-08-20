package core.domain.board.dto;

import core.global.enums.BoardCategory;

import java.time.Instant;

public record BoardResponse(
        Long postId,
        String contentPreview,
        String userName,
        BoardCategory boardCategory,
        Instant createdAt,
        Long likeCount,
        Long commentCount,
        Long viewCount,
        String userImageUrl,
        String contentImageUrl,
        Long score
) {
}

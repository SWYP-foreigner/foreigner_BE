package core.domain.board.dto;

import java.time.Instant;

public record BoardResponse(
    String title,
    String content,
    String userName,
    Instant createdTime,
    Long likeCount,
    Long commentCount,
    Long viewCount,
    String UserImageUrl,
    String contentImageUrl
) {
}

package core.domain.board.dto;

import core.domain.post.entity.Post;

import java.sql.Timestamp;
import java.time.Instant;

public record BoardResponse(
    String title,
    String content,
    String userName,
    Instant createdTime,
    Long likeCount,
    Long commentCount,
    Long viewCount,
    String thumbnailUrl
) {
    public BoardResponse fromEntity(Post post, Long likeCount, Long commentCount, Long viewCount, String  thumbnailUrl) {
        return new BoardResponse(post.getTitle(), post.getContent(), post.getAuthor().getName(), post.getCreatedAt(), likeCount, commentCount, viewCount, thumbnailUrl);
    }
}

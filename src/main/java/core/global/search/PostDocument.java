package core.global.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import core.domain.post.entity.Post;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostDocument(
        Long postId,
        Long boardId,
        Long userId,
        Boolean anonymous,
        Instant createdAt,
        Instant updatedAt,
        Long checkCount,
        String content
) {
    public PostDocument(Post p) {
        this(
                p.getId(),
                p.getBoard() != null ? p.getBoard().getId() : null,
                p.getAuthor() != null ? p.getAuthor().getId() : null,
                Boolean.TRUE.equals(p.getAnonymous()),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                p.getCheckCount(),
                p.getContent()
        );
    }
}
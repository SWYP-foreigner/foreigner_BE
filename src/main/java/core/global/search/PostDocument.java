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
        Long createdAt,
        Long updatedAt,
        Long checkCount,
        String content,
        Object contentSuggest
) {
    public PostDocument(Post p) {
        this(
                p.getId(),
                p.getBoard() != null ? p.getBoard().getId() : null,
                p.getAuthor() != null ? p.getAuthor().getId() : null,
                Boolean.TRUE.equals(p.getAnonymous()),
                p.getCreatedAt() == null ? null : p.getCreatedAt().toEpochMilli(),
                p.getUpdatedAt() == null ? null : p.getUpdatedAt().toEpochMilli(),
                p.getCheckCount(),
                p.getContent(),
                java.util.Map.of(
                        "input", java.util.List.of(p.getContent()),
                        "weight", Math.min(100, (int)Math.round(Math.log1p(
                                p.getCheckCount() == null ? 0L : p.getCheckCount()) * 20))
                )
        );
    }
}
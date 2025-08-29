package core.global.search;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@NoArgsConstructor
public class PostDocument {
    private Long postId;
    private Long boardId;
    private Long userId;
    private Boolean anonymous;
    private Instant createdAt;
    private Instant updatedAt;
    private Long checkCount;
    private String content;
}

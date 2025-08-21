package core.domain.comment.dto;

import core.domain.comment.entity.Comment;
import io.micrometer.common.lang.Nullable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(name = "CommentResponse", description = "댓글 상세 응답")
public record CommentResponse(
        @Schema(description = "댓글 ID", example = "10", nullable = true)
        Long commentId,

        @Schema(description = "작성자 이름", example = "bob", nullable = true)
        String username,

        @Schema(description = "댓글 내용", example = "좋은 글이네요!")
        String content,

        @Schema(description = "좋아요 개수", example = "5")
        Long likeCount,

        @Schema(description = "작성일", type = "string", format = "date-time", example = "2025-08-21T09:20:00Z")
        Instant createdAt,

        @Schema(description = "작성자 프로필 이미지 URL", example = "https://cdn.example.com/u/bob.png", nullable = true)
        String userImage,

        @Schema(description = "삭제 여부", example = "false")
        Boolean deleted
) {
    public static CommentResponse from(Comment c,
                                       long likeCount,
                                       @Nullable String userImageUrl) {
        if (c.isDeleted()) {
            return new CommentResponse(
                    null,
                    null,
                    "삭제된 댓글입니다.",
                    0L,
                    c.getCreatedAt(),
                    null,
                    true
            );
        }
        return new CommentResponse(
                c.getId(),
                (c.getAuthor() != null) ? c.getAuthor().getName() : null,
                c.getContent(),
                likeCount,
                c.getCreatedAt(),
                userImageUrl,
                false
        );
    }
}

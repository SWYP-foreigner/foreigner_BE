package core.domain.comment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(name = "CommentCursorPageResponse", description = "댓글 커서 기반 페이지 응답")
public record CommentCursorPageResponse<T>(
        @Schema(description = "댓글 리스트")
        List<T> items,

        @Schema(description = "다음 페이지 존재 여부", example = "true")
        boolean hasNext,

        @Schema(description = "다음 커서 생성일 (최신 정렬 시 사용)", type = "string", format = "date-time", example = "2025-08-21T12:34:56Z", nullable = true)
        Instant nextCursorCreatedAt,

        @Schema(description = "다음 커서 댓글 ID", example = "200", nullable = true)
        Long nextCursorId,

        @Schema(description = "다음 커서 좋아요 수 (인기 정렬 시 사용)", example = "50", nullable = true)
        Long nextCursorLikeCount
) {}
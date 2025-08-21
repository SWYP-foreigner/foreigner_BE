package core.domain.comment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "UserCommentsSliceResponse", description = "사용자 댓글 슬라이스 응답")
public record UserCommentsSliceResponse(
        @Schema(description = "댓글 아이템 리스트")
        List<UserCommentItem> items,

        @Schema(description = "다음 커서 ID", example = "150", nullable = true)
        Long nextCursor,

        @Schema(description = "다음 페이지 존재 여부", example = "true")
        boolean hasNext
) {}
package core.domain.bookmark.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "BookmarkCursorPageResponse", description = "북마크 커서 기반 페이지 응답")
public record BookmarkCursorPageResponse<T>(
        @Schema(description = "북마크 아이템 리스트")
        List<T> items,

        @Schema(description = "다음 페이지의 커서 ID", example = "123", nullable = true)
        Long nextCursorId,

        @Schema(description = "다음 페이지 존재 여부", example = "true")
        boolean hasNext
) {}
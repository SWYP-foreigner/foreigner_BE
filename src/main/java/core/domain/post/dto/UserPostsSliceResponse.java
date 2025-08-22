package core.domain.post.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "UserPostsSliceResponse", description = "사용자가 작성한 게시글 리스트(슬라이스) 응답 DTO")
public record UserPostsSliceResponse(
        @Schema(description = "게시글 아이템 리스트")
        List<UserPostItem> items,

        @Schema(description = "다음 페이지 커서 ID", example = "101", nullable = true)
        Long nextCursor,

        @Schema(description = "다음 페이지 존재 여부", example = "true")
        boolean hasNext
) {}

package core.domain.post.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/api/v1/board")
@RestController
@Validated
@Tag(name = "Posts", description = "게시글/작성/좋아요 API")
@SecurityRequirement(name = "bearerAuth")
public class PostController {
//    @GetMapping("/")
    @Operation(summary = "게시글 상세 조회", description = "특정 보드의 게시글 상세를 반환합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "성공",
            content = @Content(schema = @Schema(implementation = PostDetailResponse.class))
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "게시글 없음", content = @Content)
    })
    @GetMapping("/{boardId}/{postId}")
    public ResponseEntity<ApiResponse<PostDetailResponse>> getPostDetail(
            Authentication authentication,
            @Parameter(description = "보드 ID", example = "10")
            @PathVariable @Positive Long boardId,
            @Parameter(description = "게시글 ID", example = "123")
            @PathVariable @Positive Long postId) {

        return ResponseEntity.ok(ApiResponse.success(
                postService.getPostDetail(boardId, postId)
        ));
    }

}

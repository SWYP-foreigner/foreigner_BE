package core.domain.post.controller;

import core.domain.post.dto.PostDetailResponse;
import core.domain.post.dto.PostUpdateRequest;
import core.domain.post.dto.PostWriteAnonymousAvailableResponse;
import core.domain.post.dto.PostWriteRequest;
import core.domain.post.service.PostService;
import core.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RequestMapping("/api/v1/board")
@RestController
@Validated
@Tag(name = "Posts", description = "게시글/작성/좋아요 API")
@SecurityRequirement(name = "bearerAuth")
public class PostController {

    private final PostService postService;

    PostController(PostService postService) {
        this.postService = postService;
    }

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

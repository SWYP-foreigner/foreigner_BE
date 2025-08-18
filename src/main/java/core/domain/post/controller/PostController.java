package core.domain.post.controller;

import core.domain.post.dto.PostDetailResponse;
import core.domain.post.dto.PostUpdateRequest;
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

@RequestMapping("/api/v1")
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
    @GetMapping("/posts/{postId}")
    public ResponseEntity<ApiResponse<PostDetailResponse>> getPostDetail(
            Authentication authentication,
            @Parameter(description = "게시글 ID", example = "123")
            @PathVariable @Positive Long postId) {

        return ResponseEntity.ok(ApiResponse.success(
                postService.getPostDetail(postId)
        ));
    }

    @Operation(summary = "게시글 작성", description = "본문/이미지/익명 여부를 포함해 게시글을 작성합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "성공",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "검증 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음", content = @Content)
    })
    @PostMapping("/boards/{boardId}/posts")
    public ResponseEntity<ApiResponse<?>> writePost(
//            Authentication authentication,
            @PathVariable @Positive Long boardId,
            @Valid @RequestBody PostWriteRequest writeRequest) {

        postService.writePost("authentication.getName()", boardId, writeRequest);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("게시글 작성 완료"));
    }

    @Operation(summary = "게시글 수정", description = "게시글을 수정합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "수정 성공(본문 없음)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "검증 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "게시글 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "충돌", content = @Content)
    })
    @PutMapping("/posts/{postId}")
    public ResponseEntity<ApiResponse<?>> updatePost(
//            Authentication authentication,
            @Parameter(description = "보드 ID", example = "10") @PathVariable @Positive Long boardId,
            @Parameter(description = "게시글 ID", example = "123") @PathVariable @Positive Long postId,
            @Valid @RequestBody PostUpdateRequest updateRequest) {

        postService.updatePost("authentication.getName()", postId, updateRequest);
        return ResponseEntity
                .status(HttpStatus.NO_CONTENT)
                .body(ApiResponse.success("게시글 수정 완료"));
    }

    @Operation(summary = "게시글 삭제", description = "게시글을 삭제합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "삭제 성공(본문 없음)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "게시글 없음", content = @Content)
    })
    @DeleteMapping("/posts/{postId}")
    public ResponseEntity<ApiResponse<?>> deletePost(
//            Authentication authentication,
            @Parameter(description = "보드 ID", example = "10") @PathVariable @Positive Long boardId,
            @Parameter(description = "게시글 ID", example = "123") @PathVariable @Positive Long postId
    ) {

        postService.deletePost("authentication.getName()", postId);

        return ResponseEntity
                .status(HttpStatus.NO_CONTENT)
                .body(ApiResponse.success("게시글 삭제 완료"));
    }


    @Operation(summary = "나의 게시글 리스트 조회", description = "나의 게시글 리스트를 반환합니다.")
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
    @GetMapping("/my/posts")
    public ResponseEntity<ApiResponse<PostDetailResponse>> getMyPostList(
            Authentication authentication,
            @Parameter(description = "게시글 ID", example = "123")
            @PathVariable @Positive Long postId) {

        return ResponseEntity.ok(ApiResponse.success(
                postService.getMyPostList(postId)
        ));
    }


    @Operation(summary = "게시글 좋아요 설정", description = "좋아요 설정합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "성공",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "게시글 없음", content = @Content)
    })
    @PutMapping("/posts/{postId}/likes/me")
    public ResponseEntity<ApiResponse<?>> addLike(
            Authentication authentication,
            @Parameter(description = "게시글 ID", example = "123") @PathVariable @Positive Long postId
            ) {

        postService.addLike(authentication.getName(), postId);
        return ResponseEntity
                .status(HttpStatus.NO_CONTENT)
                .body(ApiResponse.success("좋아요 설정"));
    }

    @Operation(summary = "게시글 좋아요 해제", description = "좋아요 해제합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "성공",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "게시글 없음", content = @Content)
    })
    @DeleteMapping("/posts/{postId}/likes/me")
    public ResponseEntity<ApiResponse<?>> unlike(
            Authentication authentication,
            @PathVariable @Positive Long postId
    ) {
        postService.removeLike(authentication.getName(), postId);
        return ResponseEntity
                .status(HttpStatus.NO_CONTENT)
                .body(ApiResponse.success("좋아요 해제"));
    }

}

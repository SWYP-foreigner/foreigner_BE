package core.domain.post.controller;

import core.domain.post.dto.admin.PostReportRequest;
import core.domain.post.dto.comunity.*;
import core.domain.post.service.PostService;
import core.global.config.CustomUserDetails;
import core.global.docs.annotations.*;
import core.global.enums.errorcode.*;
import core.global.metrics.FeatureUsageMetrics;
import core.global.pagination.CursorPageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RequestMapping("/api/v1")
@RestController
@Validated
@Tag(name = "Posts", description = "게시글/작성/좋아요 API")
@GlobalErrorDocs({GlobalErrorCode.INTERNAL_SERVER_ERROR, GlobalErrorCode.INVALID_INPUT, GlobalErrorCode.INVALID_JSON, GlobalErrorCode.METHOD_NOT_ALLOWED})
public class PostController {

    private final PostService postService;
    private final FeatureUsageMetrics featureUsageMetrics;


    PostController(PostService postService, FeatureUsageMetrics featureUsageMetrics) {
        this.postService = postService;
        this.featureUsageMetrics = featureUsageMetrics;
    }

    @Operation(summary = "게시글 상세 조회", description = "특정 보드의 게시글 상세를 반환합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "성공"
    )
    @GetMapping("/posts/{postId}")
    @CommunityErrorDocs({CommunityErrorCode.POST_NOT_FOUND, CommunityErrorCode.BLOCKED_USER_POST})
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND, UserErrorCode.PROFILE_SET_NOT_COMPLETED})
    @CommonErrorCodeDocs({CommonErrorCode.TRANSLATE_FAIL})
    public ResponseEntity<core.global.dto.ApiResponse<PostDetailResponse>> getPostDetail(
            @Parameter(description = "게시글 ID", example = "123")
            @PathVariable @Positive Long postId,
            @RequestParam(defaultValue = "false") Boolean translate) {

        featureUsageMetrics.recordCommunityUsage();
        return ResponseEntity.ok(core.global.dto.ApiResponse.success(
                postService.getPostDetail(postId, translate)
        ));
    }

    @Operation(summary = "게시글 작성", description = "본문/이미지/익명 여부를 포함해 게시글을 작성합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "성공",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
    })
    @PostMapping("/boards/{boardId}/posts")
    @CommunityErrorDocs({CommunityErrorCode.NOT_AVAILABLE_WRITE, CommunityErrorCode.BOARD_NOT_FOUND, CommunityErrorCode.NOT_AVAILABLE_ANONYMOUS, CommunityErrorCode.DUPLICATE_CONTENT,CommunityErrorCode.TOO_MANY_POSTS})
    @CommonErrorCodeDocs({CommonErrorCode.FORBIDDEN_WORD_DETECTED})
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND, UserErrorCode.PROFILE_SET_NOT_COMPLETED})
    @ImageErrorCodeDocs({ImageErrorCode.POST_IMAGES_ALREADY_EXIST,ImageErrorCode.IMAGE_UPLOAD_FAILED})
    public ResponseEntity<core.global.dto.ApiResponse<Long>> writePost(
            @PathVariable @Positive Long boardId,
            @Valid @RequestBody PostWriteRequest writeRequest) {

        Long postId = postService.writePost(boardId, writeRequest);
        featureUsageMetrics.recordCommunityUsage();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(core.global.dto.ApiResponse.success(postId));
    }

    @Operation(summary = "채팅 게시글 작성", description = "채팅 링크에서 넘어와서 본문/이미지/익명 여부를 포함해 게시글을 작성합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "성공",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
    })
    @PostMapping("/chat/rooms/{roomId}/share")
    @CommunityErrorDocs({CommunityErrorCode.NOT_AVAILABLE_WRITE, CommunityErrorCode.BOARD_NOT_FOUND, CommunityErrorCode.NOT_AVAILABLE_ANONYMOUS, CommunityErrorCode.DUPLICATE_CONTENT,CommunityErrorCode.TOO_MANY_POSTS, CommunityErrorCode.NOT_AVAILABLE_LINK})
    @CommonErrorCodeDocs({CommonErrorCode.FORBIDDEN_WORD_DETECTED})
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND, UserErrorCode.PROFILE_SET_NOT_COMPLETED})
    @ImageErrorCodeDocs({ImageErrorCode.POST_IMAGES_ALREADY_EXIST,ImageErrorCode.IMAGE_UPLOAD_FAILED})
    public ResponseEntity<core.global.dto.ApiResponse<?>> writePostForChat(
            @PathVariable @Positive Long roomId,
            @Valid @RequestBody PostWriteForChatRequest writeRequest) {

        postService.writePostForChat(roomId, writeRequest);
        featureUsageMetrics.recordCommunityUsage();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(core.global.dto.ApiResponse.success("게시글 작성 완료"));
    }

    @Operation(summary = "게시글 수정", description = "게시글을 수정합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "수정 성공(본문 없음)", content = @Content),
    })
    @PutMapping("/posts/{postId}")
    @CommunityErrorDocs({CommunityErrorCode.POST_NOT_FOUND, CommunityErrorCode.POST_EDIT_FORBIDDEN})
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    @ImageErrorCodeDocs({ImageErrorCode.IMAGE_UPLOAD_FAILED})
    @CommonErrorCodeDocs({CommonErrorCode.FORBIDDEN_WORD_DETECTED})
    public ResponseEntity<core.global.dto.ApiResponse<?>> updatePost(
            @Parameter(description = "게시글 ID", example = "123") @PathVariable @Positive Long postId,
            @Valid @RequestBody PostUpdateRequest updateRequest) {

        postService.updatePost( postId, updateRequest);
        featureUsageMetrics.recordCommunityUsage();
        return ResponseEntity
                .status(HttpStatus.NO_CONTENT)
                .body(core.global.dto.ApiResponse.success("게시글 수정 완료"));
    }

    @Operation(summary = "게시글 삭제", description = "게시글을 삭제합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "삭제 성공(본문 없음)", content = @Content),
    })
    @DeleteMapping("/posts/{postId}")
    @CommunityErrorDocs({CommunityErrorCode.POST_NOT_FOUND, CommunityErrorCode.POST_DELETE_FORBIDDEN})
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND, UserErrorCode.PROFILE_SET_NOT_COMPLETED})
    @ImageErrorCodeDocs({ImageErrorCode.IMAGE_FOLDER_DELETE_FAILED})
    public ResponseEntity<core.global.dto.ApiResponse<?>> deletePost(
            @Parameter(description = "게시글 ID", example = "123") @PathVariable @Positive Long postId
    ) {

        postService.deletePost(postId);
        featureUsageMetrics.recordCommunityUsage();
        return ResponseEntity
                .status(HttpStatus.NO_CONTENT)
                .body(core.global.dto.ApiResponse.success("게시글 삭제 완료"));
    }


    @Operation(
            summary = "나의 게시글 리스트 조회",
            description = """
                      - 정렬: createdAt DESC, postId DESC
                      - 무한스크롤: 응답의 `nextCursor`를 다음 호출의 `cursor`로 그대로 전달
                    
                      요청 예시
                      1) 첫 페이지:
                         GET /api/v1/boards/my/posts?size=20
                      2) 다음 페이지:
                         GET /api/v1/boards/my/posts?size=20&cursor=eyJ0IjoiMjAyNS0wOC0yMVQxMjowMDowMFoiLCJpZCI6MTAxfQ
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200", description = "성공"
            ),
    })
    @GetMapping("/my/posts")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND, UserErrorCode.PROFILE_SET_NOT_COMPLETED})
    public ResponseEntity<core.global.dto.ApiResponse<CursorPageResponse<UserPostItem>>> getMyPostList(
            @Parameter(description = "페이지 크기(1~50)", example = "20") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "응답의 nextCursor를 그대로 입력(첫 페이지는 비움)",
                    example = "eyJ0IjoiMjAyNS0wOC0yMVQxMjowMDowMFoiLCJpZCI6MTAxfQ")
            @RequestParam(required = false) String cursor
    ) {
        featureUsageMetrics.recordCommunityUsage();

        return ResponseEntity.ok(
                core.global.dto.ApiResponse.success(
                        postService.getMyPostList( cursor, size)
                )
        );
    }


    @Operation(summary = "게시글 좋아요 설정", description = "좋아요 설정합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "성공",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
    })
    @PutMapping("/posts/{postId}/likes/me")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND, UserErrorCode.PROFILE_SET_NOT_COMPLETED})
    @CommunityErrorDocs(CommunityErrorCode.LIKE_ALREADY_EXIST)
    public ResponseEntity<core.global.dto.ApiResponse<?>> addLike(
            @Parameter(description = "게시글 ID", example = "123") @PathVariable @Positive Long postId
    ) {

        postService.addLike(postId);
        featureUsageMetrics.recordCommunityUsage();

        return ResponseEntity
                .status(HttpStatus.NO_CONTENT)
                .body(core.global.dto.ApiResponse.success("좋아요 설정"));
    }


    @Operation(summary = "익명 댓글 쓰기 가능 여부", description = "선택한 보드에서 익명 작성이 가능한지 반환합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = PostWriteAnonymousAvailableResponse.class))),
    })
    @GetMapping("/posts/{postId}/write-options")
    public ResponseEntity<core.global.dto.ApiResponse<CommentWriteAnonymousAvailableResponse>> getWriteOptions(
            @Parameter(description = "게시글 ID", example = "10")
            @PathVariable @Positive(message = "postId는 양수여야 합니다.") Long postId) {

        return ResponseEntity.ok(core.global.dto.ApiResponse.success(
                postService.isAnonymousAvaliable(postId)
        ));
    }


    @Operation(summary = "게시글 좋아요 해제", description = "좋아요 해제합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "성공",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
    })
    @DeleteMapping("/posts/{postId}/likes/me")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND, UserErrorCode.PROFILE_SET_NOT_COMPLETED})
    public ResponseEntity<core.global.dto.ApiResponse<?>> unlike(
            @PathVariable @Positive Long postId
    ) {
        postService.removeLike(postId);
        featureUsageMetrics.recordCommunityUsage();

        return ResponseEntity
                .status(HttpStatus.NO_CONTENT)
                .body(core.global.dto.ApiResponse.success("좋아요 해제"));
    }

    @Operation(summary = "게시글 차단(신고)", description = "게시글을 차단합니다. 해당 게시물에만 유저에게 보이지 않습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "신고 접수 성공",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
    })
    @PostMapping("/posts/{postId}/declaration")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    @CommunityErrorDocs({CommunityErrorCode.POST_NOT_FOUND,CommunityErrorCode.DUPLICATE_REPORT, CommunityErrorCode.CANNOT_REPORT_SELF})
    public ResponseEntity<core.global.dto.ApiResponse<?>> blockPost(
            @PathVariable @Positive Long postId,
            @Valid @RequestBody PostReportRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        Long reporterUserId = principal.getUserId();
        postService.reportPost(reporterUserId, postId, request);
        featureUsageMetrics.recordCommunityUsage();

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(core.global.dto.ApiResponse.success("신고 접수 성공"));
    }


    @Operation(summary = "유저 차단(차단)", description = "유저을 차단합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "성공",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "게시글 없음", content = @Content)
    })
    @PostMapping("/posts/{postId}/block")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND, UserErrorCode.CANNOT_BLOCK, UserErrorCode.PROFILE_SET_NOT_COMPLETED})
    public ResponseEntity<core.global.dto.ApiResponse<?>> blockUser(
            @PathVariable @Positive Long postId
    ) {
        postService.blockUser(postId);
        featureUsageMetrics.recordCommunityUsage();

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(core.global.dto.ApiResponse.success("차단 성공"));
    }

}

package core.domain.comment.controller;

import core.domain.comment.dto.*;
import core.domain.comment.service.CommentService;
import core.global.dto.ApiResponse;
import core.global.enums.SortOption;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Comments", description = "댓글 조회/작성/수정/삭제 API")
public class CommentController {
    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @Operation(summary = "댓글 목록 조회", description = "커서 기반 페이지네이션으로 댓글 목록을 조회합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "성공",
            content = @Content(schema = @Schema(implementation = CommentCursorPageResponse.class))
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "게시글 없음", content = @Content)
    })
    @GetMapping("/posts/{postId}/comments")
    public ResponseEntity<ApiResponse<CommentCursorPageResponse<CommentResponse>>> getCommentList(
            Authentication authentication,
            @Parameter(description = "게시글 ID", example = "123") @PathVariable("postId") Long postId,
            @Parameter(description = "페이지 크기(최대 100)", example = "20") @RequestParam(defaultValue = "20") Integer size,
            @Parameter(description = "커서 기준 생성시각(ISO-8601)", example = "2025-08-01T12:00:00Z") @RequestParam(required = false) Instant cursorCreatedAt,
            @Parameter(description = "커서 기준 댓글 ID", example = "98765") @RequestParam(required = false) Long cursorId,
            @Parameter(description = "정렬 옵션", example = "LATEST", schema = @Schema(implementation = SortOption.class)) @RequestParam(defaultValue = "LATEST") SortOption sort,
            @Parameter(description = "커서 기준 좋아요 수", example = "10") @RequestParam(required = false) Long cursorLikeCount
    ) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        commentService.getCommentList(postId, size, sort, cursorCreatedAt, cursorId, cursorLikeCount)
                ));
    }

    @Operation(summary = "댓글 작성", description = "게시글에 댓글을 작성합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "성공",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "유효성 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요", content = @Content)
    })
    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<ApiResponse<?>> writeComment(
            Authentication authentication,
            @Parameter(description = "게시글 ID", example = "123") @PathVariable("postId") Long postId,
            @Valid @RequestBody CommentWriteRequest request
    ) {
        commentService.writeComment(authentication.getName(), postId, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("댓글 작성 완료"));
    }

    @Operation(summary = "댓글 수정", description = "본인이 작성한 댓글을 수정합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "수정 성공(본문 없음)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "유효성 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "댓글 없음", content = @Content)
    })
    @PatchMapping("/comments/{commentId}")
    public ResponseEntity<ApiResponse<?>> updateComment(
            Authentication authentication,
            @Parameter(description = "댓글 ID", example = "98765") @PathVariable("commentId") Long commentId,
            @Valid @RequestBody CommentUpdateRequest request
    ) {
        commentService.updateComment(authentication.getName(), commentId, request);
        return ResponseEntity
                .status(HttpStatus.NO_CONTENT)
                .body(ApiResponse.success("댓글 수정 완료"));
    }


    @Operation(summary = "댓글 삭제", description = "본인이 작성한 댓글을 삭제합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "삭제 성공(본문 없음)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "댓글 없음", content = @Content)
    })
    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<ApiResponse<?>> deleteComment(
            Authentication authentication,
            @Parameter(description = "댓글 ID", example = "98765") @PathVariable("commentId") Long commentId
    ) {
        commentService.deleteComment(authentication.getName(), commentId);
        return ResponseEntity
                .status(HttpStatus.NO_CONTENT)
                .body(ApiResponse.success("댓글 삭제 완료"));
    }


    @Operation(summary = "나의 댓글 리스트 조회", description = "나의 게시글 리스트를 반환합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "성공",
            content = @Content(schema = @Schema(implementation = UserCommentItem.class))
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "게시글 없음", content = @Content)
    })
    @GetMapping("/my/comments")
    public ResponseEntity<ApiResponse<UserCommentsSliceResponse>> getMyPostList(
            Authentication authentication,
            @RequestParam(required = false) Long lastCommentId,
            @RequestParam(defaultValue = "20") int size
    ) {

        return ResponseEntity.ok(
                ApiResponse.success(
                        commentService.getMyCommentList("authentication.getName()", lastCommentId, size)
                ));
    }
}

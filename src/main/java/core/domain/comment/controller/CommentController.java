package core.domain.comment.controller;

import core.domain.comment.dto.CommentResponse;
import core.domain.comment.dto.CursorPage;
import core.domain.comment.service.CommentService;
import core.global.dto.ApiResponse;
import core.global.enums.SortOption;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1")
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

    @PostMapping("/{boardId}/{postId}/comment/write")
    public ResponseEntity<ApiResponse<?>> writeComment(Authentication authentication,
                                                       @PathVariable("boardId") Long boardId,
                                                       @PathVariable("postId") Long postId,
                                                       @Valid @RequestBody CommentWriteRequest request
    ) {
        commentService.writeComment(authentication.getName(), postId, request);
        ApiResponse<String> response = ApiResponse.success("댓글 작성 완료");
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PutMapping("/{boardId}/{postId}/comment/{commentId}/update")
    public ResponseEntity<ApiResponse<?>> updateComment(Authentication authentication,
                                                        @PathVariable("boardId") Long boardId,
                                                        @PathVariable("postId") Long postId,
                                                        @PathVariable("commentId") Long commentId,
                                                        @Valid @RequestBody CommentUpdateRequest request
    ) {
        commentService.updateComment(authentication.getName(), commentId, request);
        ApiResponse<String> response = ApiResponse.success("댓글 수정 완료");
        return ResponseEntity
                .status(HttpStatus.NO_CONTENT)
                .body(response);
    }


    @DeleteMapping("/{boardId}/{postId}/comment/{commentId}/delete")
    public ResponseEntity<ApiResponse<?>> deleteComment(Authentication authentication,
                                                        @PathVariable("boardId") Long boardId,
                                                        @PathVariable("postId") Long postId,
                                                        @PathVariable("commentId") Long commentId
    ) {
        commentService.deleteComment(authentication.getName(), commentId);
        ApiResponse<String> response = ApiResponse.success("댓글 삭제 완료");
        return ResponseEntity
                .status(HttpStatus.NO_CONTENT)
                .body(response);
    }

}

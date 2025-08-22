package core.domain.comment.controller;

import core.domain.comment.dto.*;
import core.domain.comment.service.CommentService;
import core.global.enums.SortOption;
import core.global.pagination.CursorPageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Comments", description = "댓글 조회/작성/수정/삭제 API")
public class CommentController {
    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @Operation(
            summary = "댓글 목록 조회",
            description = """
            - 커서 기반 무한스크롤: 응답의 `nextCursor`를 다음 호출의 `cursor`로 그대로 전달하세요.
            - 정렬: LATEST(기본) | POPULAR
            """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "성공", content = @Content(
                    mediaType = "application/json",
                    examples = @ExampleObject(
                            name = "성공 예시",
                            value = """
                        {
                          "success": true,
                          "data": {
                            "items": [
                              {
                                "commentId": 98765,
                                "authorName": "익명",
                                "content": "댓글 내용...",
                                "createdAt": "2025-08-01T12:34:56Z",
                                "likeCount": 10,
                                "userImage": "https://..."
                              }
                            ],
                            "hasNext": true,
                            "nextCursor": "eyJ0IjoiMjAyNS0wOC0wMVQxMjozNDo1NloiLCJpZCI6OTg3NjUsImxjIjoxMH0"
                          }
                        }
                        """
                    )
            )),
            @ApiResponse(responseCode = "401", description = "인증 실패", content = @Content),
            @ApiResponse(responseCode = "404", description = "게시글 없음", content = @Content)
    })
    @GetMapping("/posts/{postId}/comments")
    public ResponseEntity<core.global.dto.ApiResponse<CursorPageResponse<CommentItem>>> getCommentList(
            Authentication authentication,
            @Parameter(description = "게시글 ID", example = "123") @PathVariable("postId") Long postId,
            @Parameter(description = "페이지 크기(1~100)", example = "20") @RequestParam(defaultValue = "20") Integer size,
            @Parameter(description = "정렬 옵션", example = "LATEST") @RequestParam(defaultValue = "LATEST") SortOption sort,
            @Parameter(description = "다음 페이지 호출 시 전달하는 불투명 커서(Base64). 첫 페이지는 생략")
            @RequestParam(required = false) String cursor
    ) {
        return ResponseEntity.ok(
                core.global.dto.ApiResponse.success(
                        commentService.getCommentList(postId, size, sort, cursor)
                )
        );
    }


    @Operation(summary = "댓글 작성", description = "게시글에 댓글을 작성합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "성공",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "유효성 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요", content = @Content)
    })
    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<core.global.dto.ApiResponse<?>> writeComment(
            Authentication authentication,
            @Parameter(description = "게시글 ID", example = "123") @PathVariable("postId") Long postId,
            @Valid @RequestBody CommentWriteRequest request
    ) {
        commentService.writeComment(authentication.getName(), postId, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(core.global.dto.ApiResponse.success("댓글 작성 완료"));
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
    public ResponseEntity<core.global.dto.ApiResponse<?>> updateComment(
            Authentication authentication,
            @Parameter(description = "댓글 ID", example = "98765") @PathVariable("commentId") Long commentId,
            @Valid @RequestBody CommentUpdateRequest request
    ) {
        commentService.updateComment(authentication.getName(), commentId, request);
        return ResponseEntity
                .status(HttpStatus.NO_CONTENT)
                .body(core.global.dto.ApiResponse.success("댓글 수정 완료"));
    }


    @Operation(summary = "댓글 삭제", description = "본인이 작성한 댓글을 삭제합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "삭제 성공(본문 없음)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "댓글 없음", content = @Content)
    })
    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<core.global.dto.ApiResponse<?>> deleteComment(
            Authentication authentication,
            @Parameter(description = "댓글 ID", example = "98765") @PathVariable("commentId") Long commentId
    ) {
        commentService.deleteComment(authentication.getName(), commentId);
        return ResponseEntity
                .status(HttpStatus.NO_CONTENT)
                .body(core.global.dto.ApiResponse.success("댓글 삭제 완료"));
    }


    @Operation(
            summary = "댓글 목록 조회",
            description = """
          - 정렬: LATEST(기본) | POPULAR
          - 무한스크롤: 응답의 `nextCursor`를 다음 호출의 `cursor`로 그대로 전달
          
          요청 예시
          (LATEST) 첫 페이지:
            GET /api/v1/boards/posts/123/comments?size=20&sort=LATEST
          (LATEST) 다음 페이지:
            GET /api/v1/boards/posts/123/comments?size=20&sort=LATEST&cursor=eyJ0IjoiMjAyNS0wOC0wMVQxMjozNDo1NloiLCJpZCI6OTg3NjV9
          
          (POPULAR) 첫 페이지:
            GET /api/v1/boards/posts/123/comments?size=20&sort=POPULAR
          (POPULAR) 다음 페이지:
            GET /api/v1/boards/posts/123/comments?size=20&sort=POPULAR&cursor=eyJsYyI6MzAsInQiOiIyMDI1LTA4LTAxVDEyOjM0OjU2WiIsImlkIjo5ODc2NX0
        """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "성공", content = @Content(
                    mediaType = "application/json",
                    examples = @ExampleObject(
                            value = """
                        {
                          "success": true,
                          "data": {
                            "items": [
                              { "commentId": 321, "postContent": "원글 일부...", "commentContent": "댓글...", "createdAt": "2025-08-20T12:00:00Z" }
                            ],
                            "hasNext": true,
                            "nextCursor": "eyJpZCI6MzE5fQ"
                          }
                        }
                        """
                    )
            )),
            @ApiResponse(responseCode = "401", description = "인증 실패", content = @Content),
            @ApiResponse(responseCode = "403", description = "권한 없음", content = @Content)
    })
    @GetMapping("/my/comments")
    public ResponseEntity<core.global.dto.ApiResponse<CursorPageResponse<UserCommentItem>>> getMyCommentList(
            @Parameter(hidden = true) Authentication authentication,
            @Parameter(description = "페이지 크기(1~100)", example = "20") @RequestParam(defaultValue = "20") Integer size,
            @Parameter(description = "정렬 옵션", example = "LATEST") @RequestParam(defaultValue = "LATEST") SortOption sort,
            @Parameter(description = "응답의 nextCursor를 그대로 입력(첫 페이지는 비움)",
                    example = "eyJ0IjoiMjAyNS0wOC0wMVQxMjozNDo1NloiLCJpZCI6OTg3NjV9")
            @RequestParam(required = false) String cursor
    ) {
        return ResponseEntity.ok(
                core.global.dto.ApiResponse.success(
                        commentService.getMyCommentList(authentication.getName(), size, cursor) // ✅ 오타 수정
                )
        );
    }
}

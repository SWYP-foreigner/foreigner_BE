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

    @GetMapping("/{boardId}/{postId}/comment")
    public ResponseEntity<ApiResponse<CursorPage<CommentResponse>>> getCommentList(Authentication authentication,
                                                                                   @PathVariable("boardId") Long boardId,
                                                                                   @PathVariable("postId") Long postId,
                                                                                   @RequestParam(defaultValue = "20") Integer size,
                                                                                   @RequestParam(required = false) Instant cursorCreatedAt,
                                                                                   @RequestParam(required = false) Long cursorId,
                                                                                   @RequestParam(defaultValue = "LATEST") SortOption sort,
                                                                                   @RequestParam(required = false) Long cursorLikeCount
    ) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        commentService.getCommentList(postId, size, sort, cursorCreatedAt, cursorId, cursorLikeCount)
                ));
    }


}

package core.domain.comment.service;

import core.domain.comment.dto.CommentUpdateRequest;
import core.domain.comment.dto.CommentWriteRequest;
import core.domain.comment.dto.CommentResponse;
import core.domain.comment.dto.CommentCursorPageResponse;
import core.global.enums.SortOption;
import jakarta.validation.Valid;

import java.time.Instant;

public interface CommentService {
    CommentCursorPageResponse<CommentResponse> getCommentList(Long postId, Integer size, SortOption sort, Instant cursorCreatedAt, Long cursorId, Long cursorLikeCount);

    void writeComment(String name, Long postId, CommentWriteRequest request);

    void updateComment(String name, Long postId, @Valid CommentUpdateRequest request);

    void deleteComment(String name, Long postId);
}

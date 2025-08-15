package core.domain.comment.service;

import core.domain.comment.controller.CommentUpdateRequest;
import core.domain.comment.controller.CommentWriteRequest;
import core.domain.comment.dto.CommentResponse;
import core.domain.comment.dto.CursorPage;
import core.global.enums.SortOption;
import jakarta.validation.Valid;

import java.time.Instant;

public interface CommentService {
    CursorPage<CommentResponse> getCommentList(Long postId, Integer size, SortOption sort, Instant cursorCreatedAt, Long cursorId, Long cursorLikeCount);


}

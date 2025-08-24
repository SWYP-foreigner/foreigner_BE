package core.domain.comment.service;

import core.domain.comment.dto.*;
import core.global.enums.SortOption;
import core.global.pagination.CursorPageResponse;
import jakarta.validation.Valid;

public interface CommentService {
    CursorPageResponse<CommentItem> getCommentList(Long postId, Integer size, SortOption sort, String cursor);

    void writeComment(String name, Long postId, CommentWriteRequest request);

    void updateComment(String name, Long postId, @Valid CommentUpdateRequest request);

    void deleteComment(String name, Long postId);

    CursorPageResponse<UserCommentItem> getMyCommentList(String username, int size, String cursor);
}

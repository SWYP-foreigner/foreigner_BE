package core.global.service;

import core.domain.comment.dto.CommentListResponse;
import core.domain.comment.dto.CommentSearchRequest;
import core.domain.comment.entity.Comment;
import core.domain.comment.repository.CommentRepository;
import core.domain.user.service.UserAdminService;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CommentAdminService {

    private final CommentRepository commentRepository;
    private final UserAdminService userAdminService;

    @Transactional(readOnly = true)
    public Page<CommentListResponse> searchComments(CommentSearchRequest request, Pageable pageable) {
        return commentRepository.searchComments(request, pageable);
    }

    @Transactional
    public void deleteComment(Long commentId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMENT_NOT_FOUND));

        commentRepository.delete(comment);
    }

    @Transactional
    public void deleteCommentAndBanUser(Long commentId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMENT_NOT_FOUND));

        Long authorId = comment.getAuthor().getId();

        commentRepository.delete(comment);
        userAdminService.hardDeleteUser(authorId);
    }
}

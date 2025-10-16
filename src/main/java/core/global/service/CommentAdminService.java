package core.global.service;

import core.domain.comment.dto.CommentListResponse;
import core.domain.comment.dto.CommentSearchRequest;
import core.domain.comment.entity.Comment;
import core.domain.comment.repository.CommentRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CommentAdminService {

    private final CommentRepository commentRepository;

    @Transactional(readOnly = true)
    public Page<CommentListResponse> searchComments(CommentSearchRequest request, Pageable pageable) {
        return commentRepository.searchComments(request, pageable);
    }

    @Transactional
    public void deleteComment(Long commentId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found"));

        commentRepository.delete(comment);
    }
}

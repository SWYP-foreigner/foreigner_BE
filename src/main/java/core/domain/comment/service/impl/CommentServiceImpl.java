package core.domain.comment.service.impl;

import core.domain.chat.service.ForbiddenWordService;
import core.domain.comment.dto.CommentItem;
import core.domain.comment.dto.CommentUpdateRequest;
import core.domain.comment.dto.CommentWriteRequest;
import core.domain.comment.dto.UserCommentItem;
import core.domain.comment.entity.Comment;
import core.domain.comment.repository.CommentRepository;
import core.domain.comment.service.CommentService;
import core.domain.post.entity.Post;
import core.domain.post.repository.PostRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.ErrorCode;
import core.global.enums.ImageType;
import core.global.enums.LikeType;
import core.global.enums.SortOption;
import core.global.exception.BusinessException;
import core.global.image.repository.ImageRepository;
import core.global.like.repository.LikeRepository;
import core.global.pagination.CursorCodec;
import core.global.pagination.CursorPageResponse;
import io.micrometer.common.lang.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    private final CommentRepository commentRepository;
    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final ImageRepository imageRepository;
    private final LikeRepository likeRepository;
    private final ForbiddenWordService forbiddenWordService;

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<CommentItem> getCommentList(
            Long postId, Integer size, SortOption sort, @Nullable String cursor
    ) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        Long myId = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND))
                .getId();

        final int pageSize = Math.min(Math.max(size == null ? 20 : size, 1), 100);


        Instant cursorCreatedAt = null;
        Long cursorId = null;
        Long cursorLikeCount = null;

        Map<String, Object> c = CursorCodec.decode(cursor);
        if (c.get("t") instanceof String ts && !ts.isBlank()) {
            cursorCreatedAt = Instant.parse(ts);
        }
        if (c.get("id") instanceof Number n1) {
            cursorId = n1.longValue();
        }
        if (c.get("lc") instanceof Number n2) {
            cursorLikeCount = n2.longValue();
        }

        Pageable pageableLatest = PageRequest.of(0, pageSize, Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.desc("id")
        ));
        Pageable pageablePopular = PageRequest.of(0, pageSize);

        Slice<Comment> slice;
        if (sort == SortOption.POPULAR) {
            slice = (cursorId == null || cursorLikeCount == null || cursorCreatedAt == null)
                    ? commentRepository.findPopularByPostId(myId, postId, LikeType.COMMENT, pageablePopular)
                    : commentRepository.findPopularByCursor(
                    myId, postId, LikeType.COMMENT, cursorLikeCount, cursorCreatedAt, cursorId, pageablePopular
            );
        } else {
            slice = (cursorId == null || cursorCreatedAt == null)
                    ? commentRepository.findByPostId(myId, postId, pageableLatest)
                    : commentRepository.findCommentByCursor(myId, postId, cursorCreatedAt, cursorId, pageableLatest);
        }

        List<Comment> rows = slice.getContent();
        if (rows.isEmpty()) {
            return new CursorPageResponse<>(List.of(), false, null);
        }

        // 4) 보조 데이터(좋아요 수, 유저 이미지) 일괄 조회
        List<Long> commentIds = rows.stream().map(Comment::getId).toList();

        Map<Long, Long> likeCountMap = likeRepository.countByRelatedIds(LikeType.COMMENT, commentIds).stream()
                .collect(Collectors.toMap(r -> (Long) r[0], r -> (Long) r[1]));

        List<Long> authorIds = rows.stream()
                .map(cmt -> (cmt.getAuthor() != null) ? cmt.getAuthor().getId() : null)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, String> userImageMap = authorIds.isEmpty() ? Map.of()
                : imageRepository.findUrlByRelatedIds(ImageType.USER, authorIds).stream()
                .collect(Collectors.toMap(r -> (Long) r[0], r -> (String) r[1]));

        List<CommentItem> items = rows.stream()
                .map(cmt -> {
                    long lc = likeCountMap.getOrDefault(cmt.getId(), 0L);
                    String userImage = (cmt.getAuthor() != null) ? userImageMap.get(cmt.getAuthor().getId()) : null;
                    return CommentItem.from(cmt, lc, userImage);
                })
                .toList();

        // 5) nextCursor 생성
        Comment last = rows.get(rows.size() - 1);
        String nextCursor;
        if (sort == SortOption.POPULAR) {
            long lastLc = likeCountMap.getOrDefault(last.getId(), 0L);
            nextCursor = CursorCodec.encode(Map.of(
                    "lc", lastLc,
                    "t", last.getCreatedAt().toString(), // 동률 안정성
                    "id", last.getId()
            ));
        } else {
            nextCursor = CursorCodec.encode(Map.of(
                    "t", last.getCreatedAt().toString(),
                    "id", last.getId()
            ));
        }

        return new CursorPageResponse<>(items, slice.hasNext(), nextCursor);
    }


    @Override
    @Transactional
    public void writeComment(Long postId, CommentWriteRequest request) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        if (forbiddenWordService.containsForbiddenWord(request.comment())) {
            throw new BusinessException(ErrorCode.FORBIDDEN_WORD_DETECTED);
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));

        Comment parent = null;
        if (request.parentId() != null) {
            parent = commentRepository.findById(request.parentId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.COMMENT_NOT_FOUND));
            if (!parent.getPost().getId().equals(post.getId())) {
                throw new BusinessException(ErrorCode.INVALID_PARENT_COMMENT);
            }
        }

        try {
            boolean anonymous = Boolean.TRUE.equals(request.anonymous()); // null-safe
            Comment toSave = (parent == null)
                    ? Comment.createRootComment(post, user, request.comment(), anonymous)
                    : Comment.createReplyComment(post, user, request.comment(), anonymous, parent);

            commentRepository.save(toSave);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_COMMENT_INPUT);
        }
    }

    @Override
    @Transactional
    public void updateComment(Long commentId, CommentUpdateRequest request) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMENT_NOT_FOUND));

        if (!email.equals(comment.getAuthor().getEmail())) {
            throw new BusinessException(ErrorCode.COMMENT_EDIT_FORBIDDEN);
        }

        if (comment.isDeleted()) {
            throw new BusinessException(ErrorCode.COMMENT_ALREADY_DELETED);
        }

        if (request.content() != null) {
            comment.changeContent(request.content());
        }
    }


    @Override
    @Transactional
    public void deleteComment(Long commentId) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMENT_NOT_FOUND));

        if (comment.getAuthor() == null || !comment.getAuthor().getEmail().equals(email)) {
            throw new BusinessException(ErrorCode.COMMENT_DELETE_FORBIDDEN);
        }

        boolean hasAliveChildren = commentRepository.existsByParentIdAndDeletedFalse(commentId);

        if (hasAliveChildren) {
            comment.markDeleted(email);
        } else {
            commentRepository.delete(comment);
            cleanupIfNoChildren(comment.getParent());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<UserCommentItem> getMyCommentList(int size, String cursor) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        final int pageSize = Math.min(Math.max(size, 1), 50);

        Long lastId = null;
        var payload = CursorCodec.decode(cursor);
        Object idObj = payload.get("id");
        if (idObj instanceof Number n) lastId = n.longValue();

        List<UserCommentItem> rows = commentRepository.findMyCommentsForCursor(
                email,
                lastId,
                PageRequest.of(0, pageSize + 1)
        );

        boolean hasNext = rows.size() > pageSize;
        List<UserCommentItem> items = hasNext ? rows.subList(0, pageSize) : rows;

        if (items.isEmpty()) {
            return new CursorPageResponse<>(List.of(), false, null);
        }

        UserCommentItem last = items.get(items.size() - 1);
        String nextCursor = hasNext ? CursorCodec.encodeId(last.commentId()) : null;

        return new CursorPageResponse<>(items, hasNext, nextCursor);
    }


    private void cleanupIfNoChildren(Comment parent) {
        if (parent == null) return;
        if (parent.isDeleted() && commentRepository.countByParentId(parent.getId()) == 0) {
            Comment grand = parent.getParent();
            commentRepository.delete(parent);
            cleanupIfNoChildren(grand);
        }
    }


}

package core.domain.comment.service.impl;

import core.domain.comment.controller.CommentUpdateRequest;
import core.domain.comment.controller.CommentWriteRequest;
import core.domain.comment.dto.CommentResponse;
import core.domain.comment.dto.CursorPage;
import core.domain.comment.entity.Comment;
import core.domain.comment.repository.CommentRepository;
import core.domain.comment.service.CommentService;
import core.domain.post.entity.Post;
import core.domain.post.repository.PostRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.ErrorCode;
import core.global.enums.LikeType;
import core.global.enums.SortOption;
import core.global.exception.BusinessException;
import core.global.image.entity.ImageType;
import core.global.image.repository.ImageRepository;
import core.global.like.repository.LikeRepository;
import io.micrometer.common.lang.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
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


    @Override
    @Transactional(readOnly = true)
    public CursorPage<CommentResponse> getCommentList(Long postId, Integer size, SortOption sort, @Nullable Instant cursorCreatedAt,
                                                      @Nullable Long cursorId,
                                                      @Nullable Long cursorLikeCount) {
        Sort sortSpec = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        Pageable pageable = PageRequest.of(0, size, sortSpec);

        Slice<Comment> slice;

        if (sort == SortOption.POPULAR) {
//             인기순: ORDER BY는 쿼리 안에서 서브쿼리로 처리하므로 Pageable의 sort는 의미 없음
            pageable = PageRequest.of(0, size); // size만 사용
            if (cursorCreatedAt == null || cursorId == null || cursorLikeCount == null) {
                // 초기 로드
                slice = commentRepository.findPopularByPostId(postId, LikeType.COMMENT, pageable);
            } else {
                // 커서 로드
                slice = commentRepository.findPopularByCursor(
                        postId, LikeType.COMMENT, cursorLikeCount, cursorCreatedAt, cursorId, pageable
                );
            }
        } else {
            if (cursorCreatedAt == null || cursorId == null) {
                slice = commentRepository.findByPostId(postId, pageable);
            } else {
                slice = commentRepository.findCommentByCursor(postId, cursorCreatedAt, cursorId, pageable);
            }
        }

        List<Comment> comments = slice.getContent();
        if (comments.isEmpty()) {
            return new CursorPage<>(List.of(), false, null, null, null);
        }

        List<Long> commentIds = comments.stream().map(Comment::getId).toList();

        Map<Long, Long> likeCountMap = likeRepository.countByRelatedIds(LikeType.COMMENT, commentIds).stream()
                .collect(Collectors.toMap(r -> (Long) r[0], r -> (Long) r[1]));


        List<Long> authorIds = comments.stream()
                .map(c -> (c.getAuthor() != null) ? c.getAuthor().getId() : null)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, String> userImageMap = imageRepository.findUrlByRelatedIds(ImageType.USER, authorIds).stream()
                .collect(Collectors.toMap(
                        r -> (Long) r[0],   // related_id (userId)
                        r -> (String) r[1]  // url
                ));


        List<CommentResponse> items = comments.stream()
                .map(c -> {
                    long likeCount = likeCountMap.getOrDefault(c.getId(), 0L);
                    String userImage = (c.getAuthor() != null)
                            ? userImageMap.get(c.getAuthor().getId())
                            : null;
                    return CommentResponse.from(c, likeCount, userImage);
                })
                .toList();

        Comment last = comments.getLast();
        Long nextCursorLikeCount = (sort == SortOption.POPULAR)
                ? likeCountMap.getOrDefault(last.getId(), 0L)
                : null;

        return new CursorPage<>(
                items,
                slice.hasNext(),
                last.getCreatedAt(),
                last.getId(),
                nextCursorLikeCount
        );
    }


    @Override
    public void writeComment(String name, Long postId, CommentWriteRequest request) {
        User user = userRepository.findByName(name)
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

}

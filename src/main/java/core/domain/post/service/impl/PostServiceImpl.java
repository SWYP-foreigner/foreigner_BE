package core.domain.post.service.impl;

import core.domain.board.dto.BoardCursorPageResponse;
import core.domain.board.dto.BoardResponse;
import core.domain.board.entity.Board;
import core.domain.board.repository.BoardRepository;
import core.domain.post.dto.PostDetailResponse;
import core.domain.post.dto.PostUpdateRequest;
import core.domain.post.dto.PostWriteRequest;
import core.domain.post.entity.Post;
import core.domain.post.repository.PostRepository;
import core.domain.post.service.CommentWriteAnonymousAvailableResponse;
import core.domain.post.service.PostService;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.BoardCategory;
import core.global.enums.ErrorCode;
import core.global.enums.LikeType;
import core.global.enums.SortOption;
import core.global.exception.BusinessException;
import core.global.image.entity.Image;
import core.global.image.entity.ImageType;
import core.global.image.repository.ImageRepository;
import core.global.like.entity.Like;
import core.global.like.repository.LikeRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    private final PostRepository postRepository;
    private final BoardRepository boardRepository;
    private final LikeRepository likeRepository;
    private final UserRepository userRepository;
    private final ImageRepository imageRepository;

    @Override
    @Transactional(readOnly = true)
    public BoardCursorPageResponse<BoardResponse> getPostList(Long boardId, SortOption sort, Instant cursorCreatedAt, Long cursorId, Long cursorScore, int size) {
        final Long resolvedBoardId =
                (boardId == 1L) ? null : boardId;

        if (resolvedBoardId != null && !boardRepository.existsById(resolvedBoardId)) {
            throw new BusinessException(ErrorCode.BOARD_NOT_FOUND);
        }

        int pageSize = Math.min(Math.max(size, 1), 50);

        List<BoardResponse> rows;
        switch (sort) {
            case LATEST -> {
                rows = postRepository.findLatestPosts(
                        resolvedBoardId,
                        truncateToMillis(cursorCreatedAt),
                        cursorId,
                        pageSize,
                        null
                );
                return BoardCursorPageResponse.ofLatest(
                        rows, pageSize,
                        BoardResponse::createdAt,
                        BoardResponse::postId
                );
            }
            case POPULAR -> {
                Instant since = Instant.now().minus(Duration.ofDays(10));
                rows = postRepository.findPopularPosts(
                        resolvedBoardId,
                        since,
                        cursorScore,
                        cursorId,
                        pageSize,
                        null
                );
                return BoardCursorPageResponse.ofPopular(
                        rows, pageSize,
                        BoardResponse::score,
                        BoardResponse::postId
                );
            }
            default -> {
                rows = postRepository.findLatestPosts(resolvedBoardId, truncateToMillis(cursorCreatedAt), cursorId, pageSize, null);
                return BoardCursorPageResponse.ofLatest(rows, pageSize, BoardResponse::createdAt, BoardResponse::postId);
            }
        }
    }

    private Instant truncateToMillis(Instant i) {
        return (i == null) ? null : i.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }

    @Override
    @Transactional
    public PostDetailResponse getPostDetail(Long postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));
        addViews(post);

        return postRepository.findPostDetail(postId);
    }

    private void addViews(Post post) {
        post.changeCheckCount();
    }

    @Override
    @Transactional
    public void writePost(String name, @Positive Long boardId, PostWriteRequest request) {
        if (boardId == 1) {
            throw new BusinessException(ErrorCode.NOT_AVAILABLE_WRITE);
        }
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BOARD_NOT_FOUND));

        validateAnonymousPolicy(board.getCategory(), request.isAnonymous());

        User user = userRepository.findByName(name)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        final Post post = new Post(request, user, board);
        postRepository.save(post);

        if (request.imageUrls() != null && !request.imageUrls().isEmpty()) {
            List<Image> images = new ArrayList<>();
            int position = 0;

            for (String url : request.imageUrls()) {
                if (!StringUtils.hasText(url)) continue;

                images.add(
                        Image.of(
                                ImageType.POST,
                                post.getId(),
                                url.trim(),
                                position++
                        )
                );
            }

            if (!images.isEmpty()) {
                imageRepository.saveAll(images);
            }
        }
    }

    private void validateAnonymousPolicy(BoardCategory category, Boolean isAnonymous) {
        final boolean allowAnonymous =
                category == BoardCategory.FREE_TALK || category == BoardCategory.QNA;

        if (!allowAnonymous && isAnonymous) {
            throw new BusinessException(ErrorCode.NOT_AVAILABLE_ANONYMOUS);
        }
    }

    @Override
    @Transactional
    public void updatePost(String name, Long postId, @Valid PostUpdateRequest request) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));

        if (name.equals(post.getAuthor().getName())) {
            throw new BusinessException(ErrorCode.POST_EDIT_FORBIDDEN);
        }

        if (request.content() != null) {
            post.changeContent(request.content());
        }

        final List<String> toAdd = request.images() != null ? request.images() : List.of();
        final List<String> toRemove = request.removedImages() != null ? request.removedImages() : List.of();
        if (toAdd.isEmpty() && toRemove.isEmpty()) return;

        if (!toRemove.isEmpty()) {
            imageRepository.deleteByImageTypeAndRelatedIdAndUrlIn(ImageType.POST, postId, toRemove);
        }

        List<Image> survivors = imageRepository
                .findByImageTypeAndRelatedIdOrderByPositionAsc(ImageType.POST, postId);

        int pos = 0;
        Set<String> survivorUrls = new HashSet<>();
        for (Image img : survivors) {
            img.changePosition(pos++);
            survivorUrls.add(img.getUrl());
        }

        if (!toAdd.isEmpty()) {
            for (String url : toAdd) {
                if (survivorUrls.contains(url)) continue;
                Image created = Image.of(ImageType.POST, postId, url, pos++);
                imageRepository.save(created);
            }
        }

        // 스토리지 수정 고려
    }

    @Override
    @Transactional
    public void deletePost(String name, Long postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));

        if (post.getAuthor() == null || !post.getAuthor().getName().equals(name)) {
            throw new BusinessException(ErrorCode.POST_DELETE_FORBIDDEN);
        }

        List<Image> images = imageRepository.findByImageTypeAndRelatedIdOrderByPositionAsc(ImageType.POST, postId);
        List<String> urls = images.stream().map(Image::getUrl).toList();

        if (!urls.isEmpty()) {
            imageRepository.deleteByImageTypeAndRelatedId(ImageType.POST, postId);
        }

        postRepository.delete(post);

        // 스토리지 삭제

    }

    @Override
    @Transactional
    public void addLike(String username, Long postId) {
        Optional<Like> existedLike = likeRepository.findLikeByUsernameAndType(username, postId, LikeType.POST);
        if (existedLike.isPresent()) {
            throw new BusinessException(ErrorCode.LIKE_ALREADY_EXIST);
        }

        User user = userRepository.findByName(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        likeRepository.save(Like.builder()
                .user(user)
                .type(LikeType.POST)
                .relatedId(postId)
                .build());
    }

    @Override
    @Transactional
    public void removeLike(String username, Long postId) {
        likeRepository.deleteByUserNameAndIdAndType(username, postId, LikeType.POST);
    }

    @Override
    public PostDetailResponse getMyPostList(Long postId) {
        return null;
    }

    @Override
    public CommentWriteAnonymousAvailableResponse isAnonymousAvaliable(Long postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));

        return new CommentWriteAnonymousAvailableResponse(post.getAnonymous());
    }
}

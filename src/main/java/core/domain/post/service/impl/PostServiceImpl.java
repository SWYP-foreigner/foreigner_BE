package core.domain.post.service.impl;

import core.domain.board.dto.BoardItem;
import core.domain.board.entity.Board;
import core.domain.board.repository.BoardRepository;
import core.domain.post.dto.*;
import core.domain.post.entity.Post;
import core.domain.post.repository.PostRepository;
import core.domain.post.service.PostService;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.*;
import core.global.exception.BusinessException;
import core.global.image.entity.Image;
import core.global.image.repository.ImageRepository;
import core.global.like.entity.Like;
import core.global.like.repository.LikeRepository;
import core.global.pagination.CursorCodec;
import core.global.pagination.CursorPageResponse;
import core.global.pagination.CursorPages;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    public CursorPageResponse<BoardItem> getPostList(Long boardId, SortOption sort, String  cursor, int size) {
        final Long resolvedBoardId =
                (boardId == 1L) ? null : boardId;

        if (resolvedBoardId != null && !boardRepository.existsById(resolvedBoardId)) {
            throw new BusinessException(ErrorCode.BOARD_NOT_FOUND);
        }

        int pageSize = Math.min(Math.max(size, 1), 50);

        Map<String, Object> c = CursorCodec.decode(cursor);

        Instant cursorCreatedAt = null;
        Long cursorId = null;
        Long cursorScore = null;

        switch (sort) {
            case LATEST -> {
                Object t = c.get("t");
                if (t instanceof String ts && !ts.isBlank()) {
                    cursorCreatedAt = Instant.parse(ts);
                }
                // id
                Object idObj = c.get("id");
                if (idObj instanceof Number n) cursorId = n.longValue();

                List<BoardItem> rows = postRepository.findLatestPosts(
                        resolvedBoardId,
                        truncateToMillis(cursorCreatedAt),
                        cursorId,
                        pageSize,
                        null // 정렬/필터 추가 파라미터 있으면 그대로 사용
                );

                return CursorPages.ofLatest(
                        rows, pageSize,
                        BoardItem::createdAt,
                        BoardItem::postId
                );
            }
            case POPULAR -> {
                Object scObj = c.get("sc");
                if (scObj instanceof Number n) cursorScore = n.longValue();
                // id
                Object idObj = c.get("id");
                if (idObj instanceof Number n) cursorId = n.longValue();

                // 인기 글의 기준 기간(예: 최근 10일)
                Instant since = Instant.now().minus(Duration.ofDays(10));

                List<BoardItem> rows = postRepository.findPopularPosts(
                        resolvedBoardId,
                        since,
                        cursorScore,
                        cursorId,
                        pageSize,
                        null
                );

                return CursorPages.ofPopular(
                        rows, pageSize,
                        BoardItem::score,
                        BoardItem::postId
                );
            }
            default -> {
                Object t = c.get("t");
                if (t instanceof String ts && !ts.isBlank()) {
                    cursorCreatedAt = Instant.parse(ts);
                }
                Object idObj = c.get("id");
                if (idObj instanceof Number n) cursorId = n.longValue();

                List<BoardItem> rows = postRepository.findLatestPosts(
                        resolvedBoardId,
                        truncateToMillis(cursorCreatedAt),
                        cursorId,
                        pageSize,
                        null
                );

                return CursorPages.ofLatest(
                        rows, pageSize,
                        BoardItem::createdAt,
                        BoardItem::postId
                );
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
    @Transactional(readOnly = true)
    public CursorPageResponse<UserPostItem> getMyPostList(String username, String cursor, int size) {
        final int pageSize = Math.min(Math.max(size, 1), 50);

        Instant cursorCreatedAt = null;
        Long cursorId = null;
        var payload = CursorCodec.decode(cursor);
        if (payload.get("t") instanceof String ts && !ts.isBlank()) cursorCreatedAt = Instant.parse(ts);
        if (payload.get("id") instanceof Number n) cursorId = n.longValue();

        List<UserPostItem> rows = (cursorId == null || cursorCreatedAt == null)
                ? postRepository.findMyPostsFirstByName(username, pageSize + 1)
                : postRepository.findMyPostsNextByName(username,
                cursorCreatedAt.truncatedTo(ChronoUnit.MILLIS),
                cursorId,
                pageSize + 1);

        boolean hasNext = rows.size() > pageSize;
        if (hasNext) rows = rows.subList(0, pageSize);

        if (rows.isEmpty()) {
            return new CursorPageResponse<>(List.of(), false, null);
        }

        UserPostItem last = rows.get(rows.size() - 1);
        String nextCursor = hasNext
                ? CursorCodec.encode(Map.of(
                "t", last.createdAt().toString(),
                "id", last.postId()
        ))
                : null;

        return new CursorPageResponse<>(rows, hasNext, nextCursor);
    }

    @Override
    public CommentWriteAnonymousAvailableResponse isAnonymousAvaliable(Long postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));

        return new CommentWriteAnonymousAvailableResponse(post.getAnonymous());
    }
}

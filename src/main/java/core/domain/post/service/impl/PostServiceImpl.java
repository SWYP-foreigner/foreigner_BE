package core.domain.post.service.impl;

import core.domain.board.dto.BoardItem;
import core.domain.board.entity.Board;
import core.domain.board.repository.BoardRepository;
import core.domain.chat.service.ForbiddenWordService;
import core.domain.post.dto.*;
import core.domain.post.entity.Post;
import core.domain.post.repository.PostRepository;
import core.domain.post.service.PostService;
import core.domain.user.entity.BlockUser;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.UserRepository;
import core.global.enums.*;
import core.global.exception.BusinessException;
import core.global.image.repository.ImageRepository;
import core.global.image.service.ImageService;
import core.global.like.entity.Like;
import core.global.like.repository.LikeRepository;
import core.global.pagination.CursorCodec;
import core.global.pagination.CursorPageResponse;
import core.global.pagination.CursorPages;
import core.global.search.dto.PostCreatedEvent;
import core.global.search.dto.PostDeletedEvent;
import core.global.search.dto.PostDocument;
import core.global.search.dto.PostUpdatedEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    private final PostRepository postRepository;
    private final BoardRepository boardRepository;
    private final LikeRepository likeRepository;
    private final UserRepository userRepository;
    private final ImageRepository imageRepository;
    private final ForbiddenWordService forbiddenWordService;
    private final ImageService imageService;
    private final BlockRepository blockRepository;
    private final ApplicationEventPublisher publisher;

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<BoardItem> getPostList(Long boardId, SortOption sort, String cursor, int size) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        final Long resolvedBoardId = (boardId != null && boardId == 1L) ? null : boardId;

        if (resolvedBoardId != null && !boardRepository.existsById(resolvedBoardId)) {
            throw new BusinessException(ErrorCode.BOARD_NOT_FOUND);
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        final int pageSize = Math.min(Math.max(size, 1), 50);
        final Map<String, Object> c = safeDecode(cursor);

        return switch (sort) {
            case POPULAR -> handlePopular(user.getId(), resolvedBoardId, c, pageSize);
            case LATEST -> handleLatest(user.getId(), resolvedBoardId, c, pageSize);
            default -> handleLatest(user.getId(), resolvedBoardId, c, pageSize);
        };
    }

    // ------- 정렬 핸들러 -------

    private CursorPageResponse<BoardItem> handleLatest(Long userId, Long boardId, Map<String, Object> c, int pageSize) {
        var k = parseLatest(c); // t,id
        List<BoardItem> rows = postRepository.findLatestPosts(
                userId,
                boardId,
                truncateToMillis(k.t),
                k.id,
                pageSize + 1,
                null
        );

        if (rows == null || rows.isEmpty()) {
            return new CursorPageResponse<>(List.of(), false, null);
        }

        return CursorPages.ofLatest(
                rows, pageSize,
                BoardItem::createdAt,
                BoardItem::postId
        );
    }

    private CursorPageResponse<BoardItem> handlePopular(Long userId, Long boardId, Map<String, Object> c, int pageSize) {
        var k = parsePopular(c);
        Instant since = popularSince();
        List<BoardItem> rows = postRepository.findPopularPosts(
                userId,
                boardId,
                since,
                k.sc,
                k.id,
                pageSize + 1,
                null
        );


        if (rows == null || rows.isEmpty()) {
            return new CursorPageResponse<>(List.of(), false, null);
        }

        return CursorPages.ofPopular(
                rows, pageSize,
                BoardItem::score,
                BoardItem::postId
        );
    }

    // ------- 커서 파싱 -------

    private LatestKey parseLatest(Map<String, Object> c) {
        Instant t = null;
        Long id = null;
        Object ts = c.get("t");
        if (ts instanceof String s && !s.isBlank()) t = Instant.parse(s);
        Object idObj = c.get("id");
        if (idObj instanceof Number n) id = n.longValue();
        return new LatestKey(t, id);
    }

    private PopularKey parsePopular(Map<String, Object> c) {
        Long sc = null, id = null;
        Object scObj = c.get("sc");
        if (scObj instanceof Number n) sc = n.longValue();
        Object idObj = c.get("id");
        if (idObj instanceof Number n) id = n.longValue();
        return new PopularKey(sc, id);
    }

    private Map<String, Object> safeDecode(String cursor) {
        if (cursor == null || cursor.isBlank()) return Map.of();
        try {
            return CursorCodec.decode(cursor);
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }
    private Instant popularSince() {
        return Instant.now().minus(Duration.ofDays(10));
    }

    // ------- 유틸 -------

    private Instant truncateToMillis(Instant i) {
        return (i == null) ? null : i.truncatedTo(ChronoUnit.MILLIS);
    }

    @Override
    @Transactional
    public PostDetailResponse getPostDetail(Long postId) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        if(blockRepository.existsBlockedByEmail(email)){
            throw new BusinessException(ErrorCode.BLOCKED_USER_POST);
        }

        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));

        log.info(post.getCheckCount()+" ");
        postRepository.incrementViewCount(postId);
        log.info(post.getCheckCount()+" ");

        PostDetailResponse postDetail = postRepository.findPostDetail(email, postId);
        log.info(postDetail.authorName());
        return postDetail;
    }

    @Override
    @Transactional
    public void writePost(@Positive Long boardId, PostWriteRequest request) {
        if (boardId == 1) {
            throw new BusinessException(ErrorCode.NOT_AVAILABLE_WRITE);
        }
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BOARD_NOT_FOUND));

        validateAnonymousPolicy(board.getCategory(), request.isAnonymous());

        validatePostForbiddenWord(request.content());

        final Post post = getPost(email, request, board);

        imageService.saveOrUpdatePostImages(post.getId(), request.imageUrls(), null);
    }

    @Override
    @Transactional
    public void writePostForChat(Long roomId, PostWriteForChatRequest request) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        Board board = boardRepository.findByCategory(BoardCategory.ACTIVITY)
                .orElseThrow(() -> new BusinessException(ErrorCode.BOARD_NOT_FOUND));

        validateChatRoomPolicy(board.getCategory(), request.link());

        validatePostForbiddenWord(request.content());

        final Post post = getPost(email, request, board);

        imageService.saveOrUpdatePostImages(post.getId(), request.imageUrls(), null);
    }

    private void validatePostForbiddenWord(String content) {
        if (forbiddenWordService.containsForbiddenWord(content)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_WORD_DETECTED);
        }
    }

    private void validateChatRoomPolicy(BoardCategory category, String link) {
        final boolean allowAnonymous =
                category == BoardCategory.FREE_TALK || category == BoardCategory.QNA;

        if (!allowAnonymous && !link.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_AVAILABLE_LINK);
        }

    }

    private void validateAnonymousPolicy(BoardCategory category, Boolean isAnonymous) {
        final boolean allowAnonymous =
                category == BoardCategory.FREE_TALK || category == BoardCategory.QNA;

        if (!allowAnonymous && isAnonymous) {
            throw new BusinessException(ErrorCode.NOT_AVAILABLE_ANONYMOUS);
        }
    }

    private Post getPost(String email, PostWriteRequest request, Board board) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        final Post post = new Post(request, user, board);
        Post saved = postRepository.save(post);

        publisher.publishEvent(new PostCreatedEvent(saved.getId(), new PostDocument(saved)));
        return saved;
    }

    private Post getPost(String email, PostWriteForChatRequest request, Board board) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        final Post post = new Post(request, user, board);
        Post saved = postRepository.save(post);

        publisher.publishEvent(new PostCreatedEvent(saved.getId(), new PostDocument(saved)));
        return saved;
    }

    @Override
    @Transactional
    public void updatePost(Long postId, @Valid PostUpdateRequest request) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));

        if (!email.equals(post.getAuthor().getEmail())) {
            throw new BusinessException(ErrorCode.POST_EDIT_FORBIDDEN);
        }

        boolean changed = false;

        if (request.content() != null && !request.content().equals(post.getContent())) {
            post.changeContent(request.content());
            changed = true;
        }

        imageService.saveOrUpdatePostImages(post.getId(), request.images(), request.removedImages());

        if (changed) {
            publisher.publishEvent(new PostUpdatedEvent(post.getId(), new PostDocument(post)));
        }
    }

    @Override
    @Transactional
    public void deletePost(Long postId) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));

        if (post.getAuthor() == null || !post.getAuthor().getEmail().equals(email)) {
            throw new BusinessException(ErrorCode.POST_DELETE_FORBIDDEN);
        }

        String folderPrefix = "posts/" + postId;
        try {
            imageService.deleteFolder(folderPrefix);
        } catch (BusinessException ex) {
            log.warn("S3 폴더 삭제 실패(prefix={}): {}", folderPrefix, ex.getMessage());
        }

        imageRepository.deleteByImageTypeAndRelatedId(ImageType.POST, postId);

        Long id = post.getId();
        postRepository.delete(post);
        publisher.publishEvent(new PostDeletedEvent(id));

    }

    @Override
    @Transactional
    public void addLike(Long postId) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        Optional<Like> existedLike = likeRepository.findLikeByUserEmailAndType(email, postId, LikeType.POST);
        if (existedLike.isPresent()) {
            throw new BusinessException(ErrorCode.LIKE_ALREADY_EXIST);
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        likeRepository.save(Like.builder()
                .user(user)
                .type(LikeType.POST)
                .relatedId(postId)
                .build());
    }

    @Override
    @Transactional
    public void removeLike(Long postId) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        likeRepository.deleteByUserEmailAndIdAndType(email, postId, LikeType.POST);
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<UserPostItem> getMyPostList(String cursor, int size) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        final int pageSize = Math.min(Math.max(size, 1), 50);

        Instant cursorCreatedAt = null;
        Long cursorId = null;
        var payload = CursorCodec.decode(cursor);
        if (payload.get("t") instanceof String ts && !ts.isBlank()) cursorCreatedAt = Instant.parse(ts);
        if (payload.get("id") instanceof Number n) cursorId = n.longValue();

        List<UserPostItem> rows = (cursorId == null || cursorCreatedAt == null)
                ? postRepository.findMyPostsFirstByEmail(email, pageSize + 1)
                : postRepository.findMyPostsNextByEmail(email,
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

    @Override
    @Transactional
    public void blockUser(Long postId) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        User blockedUser = postRepository.findUserByPostId(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        log.info(blockedUser.getEmail());
        log.info("user" + email);

        if (blockedUser.getEmail().equals(email)) {
            throw new BusinessException(ErrorCode.CANNOT_BLOCK);
        }

        User me = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        log.info(""+blockedUser.getId());
        log.info("user" + me.getId());
        if (blockRepository.existsBlock(me.getId(), blockedUser.getId())) {
            throw new BusinessException(ErrorCode.CANNOT_BLOCK);
        }

        blockRepository.save(new BlockUser(me, blockedUser));
    }


    private static final class LatestKey {
        final Instant t;
        final Long id;

        LatestKey(Instant t, Long id) {
            this.t = t;
            this.id = id;
        }
    }

    private static final class PopularKey {
        final Long sc;
        final Long id;

        PopularKey(Long sc, Long id) {
            this.sc = sc;
            this.id = id;
        }
    }
}

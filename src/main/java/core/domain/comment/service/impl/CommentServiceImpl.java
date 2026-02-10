package core.domain.comment.service.impl;

import core.domain.comment.dto.CommentItem;
import core.domain.comment.dto.CommentUpdateRequest;
import core.domain.comment.dto.CommentWriteRequest;
import core.domain.comment.dto.UserCommentItem;
import core.domain.comment.entity.Comment;
import core.domain.comment.repository.CommentRepository;
import core.domain.comment.service.CommentService;
import core.domain.notification.dto.NotificationEvent;
import core.domain.post.entity.Post;
import core.domain.post.repository.PostRepository;
import core.domain.user.entity.BlockUser;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.UserRepository;
import core.domain.user.service.UserRoleDetectService;
import core.global.entity.image.service.ImageService;
import core.global.entity.like.entity.Like;
import core.global.entity.like.repository.LikeRepository;
import core.global.enums.NotificationType;
import core.global.enums.common.CommunitySortOption;
import core.global.enums.common.LikeType;
import core.global.enums.community.BoardCategory;
import core.global.enums.errorcode.CommonErrorCode;
import core.global.enums.errorcode.CommunityErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import core.global.pagination.CursorCodec;
import core.global.pagination.CursorPageResponse;
import core.global.service.ForbiddenWordService;
import core.global.service.TranslationService;
import io.micrometer.common.lang.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    private static final int FLOOD_WINDOW_MINUTES = 5;  // 도배 판단 기준 시간
    private static final int FLOOD_MAX_COMMENTS = 15;       // 5분 동안 허용할 최대 댓글 수
    private static final int DUP_WINDOW_MINUTES = 5;
    private final CommentRepository commentRepository;
    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final LikeRepository likeRepository;
    private final ForbiddenWordService forbiddenWordService;
    private final BlockRepository blockRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TranslationService translationService;
    private final UserRoleDetectService userRoleDetectService;
    private final ImageService imageService;

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<CommentItem> getCommentList(
            Long postId, Integer size, CommunitySortOption sort, @Nullable String cursor, Boolean translate) {

        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = getUserOrThrow(email);
        userRoleDetectService.isProfileSetUpUser(user);

        Long myId = user.getId();
        int pageSize = clampPageSize(size);

        Cur cur = decodeCursor(cursor);
        Pageable pageableLatest = PageRequest.of(0, pageSize, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Pageable pageablePopular = PageRequest.of(0, pageSize);

        Slice<Comment> slice = fetchSlice(sort, myId, postId, cur, pageableLatest, pageablePopular);
        List<Comment> rows = slice.getContent();
        if (rows.isEmpty()) return new CursorPageResponse<>(List.of(), false, null);

        Aux aux = loadAuxData(rows, myId);

        // 번역 대상/결과 준비 (translate == true 일 때만)
        List<Comment> targets = prepareTranslationTargets(rows, translate);
        List<String> translated = translateContents(targets, translate, user.getTranslateLanguage());

        // items 매핑 + 번역 치환
        List<CommentItem> items = mapItemsWithOptionalTranslation(
                rows, aux, targets, translated, Boolean.TRUE.equals(translate)
        );

        String nextCursor = buildNextCursor(sort, rows.get(rows.size() - 1), aux.likeCountMap());
        return new CursorPageResponse<>(items, slice.hasNext(), nextCursor);
    }

    @Override
    @Transactional
    public void writeComment(Long postId, CommentWriteRequest request) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        validateCommentForbiddenWord(request.comment());

        User user = getUserOrThrow(email);
        userRoleDetectService.isProfileSetUpUser(user);

        validateDuplicateContent(email, request.comment());

        validateCommentFlooding(email);

        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.POST_NOT_FOUND));

        Comment parent = null;
        if (request.parentId() != null) {
            parent = commentRepository.findById(request.parentId())
                    .orElseThrow(() -> new BusinessException(CommunityErrorCode.COMMENT_NOT_FOUND));
            if (!parent.getPost().getId().equals(post.getId())) {
                throw new BusinessException(CommunityErrorCode.INVALID_PARENT_COMMENT);
            }
        }

        try {
            validateAnonymousPolicy(post.getBoard().getCategory(), request.anonymous());
            Comment toSave = (parent == null)
                    ? Comment.createRootComment(post, user, request.comment(), request.anonymous())
                    : Comment.createReplyComment(post, user, request.comment(), request.anonymous(), parent);

            Comment savedComment = commentRepository.save(toSave);
            // --- 알림 이벤트 구분 발행 ---
            if (parent == null) {
                // 게시글에 댓글 작성 시 → 게시글 작성자에게 알림
                if (!post.getAuthor().getId().equals(user.getId())) {
                    NotificationEvent event = new NotificationEvent(
                            post.getAuthor().getId(),
                            user.getId(),
                            NotificationType.post,
                            post.getId(),
                            savedComment.getId(), // ✅ 2. 저장된 댓글의 ID를 이벤트에 추가
                            request.comment(),
                            null
                    );
                    eventPublisher.publishEvent(event);
                }
            } else {
                // 대댓글 작성 시 → 부모 댓글 작성자에게 알림
                if (!parent.getAuthor().getId().equals(user.getId())) {
                    NotificationEvent event = new NotificationEvent(
                            parent.getAuthor().getId(),
                            user.getId(),
                            NotificationType.comment,
                            post.getId(),
                            savedComment.getId(), // ✅ 2. 저장된 답글의 ID를 이벤트에 추가
                            request.comment(),
                            null
                    );
                    eventPublisher.publishEvent(event);
                }
            }
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommunityErrorCode.INVALID_COMMENT_INPUT);
        }
    }

    private void validateCommentForbiddenWord(String content) {
        List<String> forbiddenWords = forbiddenWordService.containsForbiddenWord(content);

        if (!forbiddenWords.isEmpty()) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN_WORD_DETECTED, forbiddenWords);
        }
    }

    private void validateAnonymousPolicy(BoardCategory category, Boolean isAnonymous) {
        final boolean allowAnonymous =
                category == BoardCategory.FREE_TALK || category == BoardCategory.QNA;

        if (!allowAnonymous && isAnonymous) {
            throw new BusinessException(CommunityErrorCode.NOT_AVAILABLE_ANONYMOUS);
        }
    }

    @Override
    @Transactional
    public void updateComment(Long commentId, CommentUpdateRequest request) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        User user = getUserOrThrow(email);
        userRoleDetectService.isProfileSetUpUser(user);

        validateCommentForbiddenWord(request.content());

        validateDuplicateContent(email, request.content());

        validateCommentFlooding(email);

        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.COMMENT_NOT_FOUND));

        if (!email.equals(comment.getAuthor().getEmail())) {
            throw new BusinessException(CommunityErrorCode.COMMENT_EDIT_FORBIDDEN);
        }

        if (comment.isDeleted()) {
            throw new BusinessException(CommunityErrorCode.COMMENT_ALREADY_DELETED);
        }

        if (request.content() != null) {
            comment.changeContent(request.content());
        }
    }

    @Override
    @Transactional
    public void deleteComment(Long commentId) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        User user = getUserOrThrow(email);
        userRoleDetectService.isProfileSetUpUser(user);

        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.COMMENT_NOT_FOUND));

        if (comment.getAuthor() == null || !comment.getAuthor().getEmail().equals(email)) {
            throw new BusinessException(CommunityErrorCode.COMMENT_DELETE_FORBIDDEN);
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

        User user = getUserOrThrow(email);
        userRoleDetectService.isProfileSetUpUser(user);

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

    @Override
    public void addLike(Long commentId) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        User user = getUserOrThrow(email);
        userRoleDetectService.isProfileSetUpUser(user);

        Optional<Like> existedLike = likeRepository.findLikeByUserEmailAndType(email, commentId, LikeType.COMMENT);
        if (existedLike.isPresent()) {
            throw new BusinessException(CommunityErrorCode.LIKE_ALREADY_EXIST);
        }


        likeRepository.save(Like.builder()
                .user(user)
                .type(LikeType.COMMENT)
                .relatedId(commentId)
                .build());
    }

    @Override
    @Transactional
    public void deleteLike(Long commentId) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        User user = getUserOrThrow(email);
        userRoleDetectService.isProfileSetUpUser(user);

        likeRepository.deleteByUserEmailAndIdAndType(email, commentId, LikeType.COMMENT);
    }

    @Override
    @Transactional
    public void blockUser(Long commentId) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        User user = getUserOrThrow(email);
        userRoleDetectService.isProfileSetUpUser(user);

        User blockedUser = commentRepository.findUserByCommentId(commentId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        if (blockedUser.getEmail().equals(email)) {
            throw new BusinessException(UserErrorCode.CANNOT_BLOCK);
        }

        User me = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        if (blockRepository.existsBlock(me.getId(), blockedUser.getId()) || blockRepository.existsBlock(blockedUser.getId(), me.getId())) {
            throw new BusinessException(UserErrorCode.CANNOT_BLOCK);
        }

        blockRepository.save(new BlockUser(me, blockedUser));
    }

    private void validateCommentFlooding(String email) {
        Instant cutOff = Instant.now().minus(FLOOD_WINDOW_MINUTES, ChronoUnit.MINUTES);

        long recentCommentCount =
                commentRepository.countByAuthorEmailAndCreatedAtAfter(email, cutOff);

        if (recentCommentCount >= FLOOD_MAX_COMMENTS) {
            throw new BusinessException(CommunityErrorCode.TOO_MANY_COMMENTS);
        }
    }

    private void validateDuplicateContent(String email, String rawContent) {
        // 1) 내용 정규화 (원하는 만큼만)
        String normalizedContent = normalizeContent(rawContent);

        // 2) 5분 전 시점
        Instant cutOff = Instant.now().minus(DUP_WINDOW_MINUTES, ChronoUnit.MINUTES);

        // 3) 같은 유저 + 같은 내용 + 5분 이내
        boolean exists = commentRepository
                .existsByAuthorEmailAndContentAndCreatedAtAfter(email, normalizedContent, cutOff);

        if (exists) {
            throw new BusinessException(CommunityErrorCode.DUPLICATE_CONTENT);
        }
    }

    private String normalizeContent(String content) {
        if (content == null) {
            return null;
        }

        String result = content;
        result = Normalizer.normalize(result, Normalizer.Form.NFC);
        result = result.replaceAll("\\s+", " ").trim();
        result = result.toLowerCase(Locale.ROOT);
        return result;
    }


    private void cleanupIfNoChildren(Comment parent) {
        if (parent == null) return;
        if (parent.isDeleted() && commentRepository.countByParentId(parent.getId()) == 0) {
            Comment grand = parent.getParent();
            commentRepository.delete(parent);
            cleanupIfNoChildren(grand);
        }
    }

    private User getUserOrThrow(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }

    private int clampPageSize(Integer size) {
        int s = (size == null) ? 20 : size;
        return Math.min(Math.max(s, 1), 100);
    }

    private Cur decodeCursor(@Nullable String cursor) {
        if (cursor == null || cursor.isBlank()) return new Cur(null, null, null);
        Map<String, Object> m = CursorCodec.decode(cursor);
        Instant t = (m.get("t") instanceof String s && !s.isBlank()) ? Instant.parse(s) : null;
        Long id = (m.get("id") instanceof Number n1) ? n1.longValue() : null;
        Long lc = (m.get("lc") instanceof Number n2) ? n2.longValue() : null;
        return new Cur(t, id, lc);
    }

    private Slice<Comment> fetchSlice(
            CommunitySortOption sort, Long myId, Long postId, Cur cur,
            Pageable pageableLatest, Pageable pageablePopular) {
        if (sort == CommunitySortOption.POPULAR) {
            return (cur.id == null || cur.lc == null || cur.t == null)
                    ? commentRepository.findPopularByPostId(myId, postId, LikeType.COMMENT, pageablePopular)
                    : commentRepository.findPopularByCursor(myId, postId, LikeType.COMMENT, cur.lc, cur.t, cur.id, pageablePopular);
        } else {
            return (cur.id == null || cur.t == null)
                    ? commentRepository.findByPostId(myId, postId, pageableLatest)
                    : commentRepository.findCommentByCursor(myId, postId, cur.t, cur.id, pageableLatest);
        }
    }

    private Aux loadAuxData(List<Comment> rows, Long myId) {
        List<Long> commentIds = rows.stream().map(Comment::getId).toList();

        Set<Long> myLikedIds = commentIds.isEmpty()
                ? Set.of()
                : new HashSet<>(likeRepository.findMyLikedRelatedIds(myId, LikeType.COMMENT, commentIds));

        Map<Long, Long> likeCountMap = likeRepository.countByRelatedIds(LikeType.COMMENT, commentIds).stream()
                .collect(Collectors.toMap(r -> (Long) r[0], r -> (Long) r[1]));

        List<Long> authorIds = rows.stream()
                .filter(cmt -> !Boolean.TRUE.equals(cmt.getAnonymous()))
                .map(cmt -> (cmt.getAuthor() != null) ? cmt.getAuthor().getId() : null)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, String> userImageMap;
        if (authorIds.isEmpty()) {
            userImageMap = Map.of();
        } else {
            userImageMap = authorIds.stream()
                    .map(userId -> Map.entry(userId, imageService.getUserProfileKey(userId))) // 첫 이미지 URL 또는 null
                    .filter(e -> e.getValue() != null)                             // null URL 제거
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (existing, incoming) -> existing,
                            LinkedHashMap::new
                    ));
        }

        return new Aux(likeCountMap, myLikedIds, userImageMap);
    }

    private List<Comment> prepareTranslationTargets(List<Comment> rows, Boolean translate) {
        if (!Boolean.TRUE.equals(translate)) return List.of();
        return rows.stream()
                .filter(c -> !c.isDeleted())
                .filter(c -> c.getContent() != null && !c.getContent().isBlank())
                .toList();
    }

    private List<String> translateContents(List<Comment> targets, Boolean translate, String targetLang) {
        if (!Boolean.TRUE.equals(translate) || targets.isEmpty()) return List.of();
        List<String> src = targets.stream().map(Comment::getContent).toList();
        return translationService.translateComments(src, targetLang);
    }

    private List<CommentItem> mapItemsWithOptionalTranslation(
            List<Comment> rows,
            Aux aux,
            List<Comment> targets,
            List<String> translated,
            boolean doTranslate
    ) {
        // targets 순서를 rows와 동일하게 만들었으므로 인덱스 소비 방식 사용
        Set<Long> targetIds = targets.stream().map(Comment::getId).collect(Collectors.toSet());
        AtomicInteger idx = new AtomicInteger(0);

        return rows.stream()
                .map(cmt -> {
                    long lc = aux.likeCountMap().getOrDefault(cmt.getId(), 0L);
                    String userImage = (cmt.getAuthor() != null) ? aux.userImageMap().get(cmt.getAuthor().getId()) : null;
                    boolean isLiked = aux.myLikedIds().contains(cmt.getId());

                    CommentItem it = CommentItem.from(cmt, isLiked, lc, userImage);

                    if (!doTranslate || it.deleted() || it.commentId() == null || !targetIds.contains(it.commentId())) {
                        return it;
                    }
                    int i = idx.getAndIncrement();
                    if (i >= translated.size()) return it; // 안전 가드

                    return new CommentItem(
                            it.commentId(),
                            it.parentCommentId(),
                            it.authorId(),
                            it.authorName(),
                            translated.get(i),   // 번역된 content 주입
                            it.isAnonymous(),
                            it.isLiked(),
                            it.likeCount(),
                            it.createdAt(),
                            it.userImage(),
                            it.deleted()
                    );
                })
                .toList();
    }

    private String buildNextCursor(CommunitySortOption sort, Comment last, Map<Long, Long> likeCountMap) {
        if (sort == CommunitySortOption.POPULAR) {
            long lastLc = likeCountMap.getOrDefault(last.getId(), 0L);
            return CursorCodec.encode(Map.of(
                    "lc", lastLc,
                    "t", last.getCreatedAt().toString(),
                    "id", last.getId()
            ));
        }
        return CursorCodec.encode(Map.of(
                "t", last.getCreatedAt().toString(),
                "id", last.getId()
        ));
    }

    private record Cur(Instant t, Long id, Long lc) {
    }

    private record Aux(
            Map<Long, Long> likeCountMap,
            Set<Long> myLikedIds,
            Map<Long, String> userImageMap
    ) {
    }
}

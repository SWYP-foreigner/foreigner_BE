package core.domain.post.repository.impl;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.*;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import core.domain.board.dto.BoardItem;
import core.domain.board.entity.QBoard;
import core.domain.comment.entity.QComment;
import core.domain.post.dto.PostDetailResponse;
import core.domain.post.dto.UserPostItem;
import core.domain.post.entity.QPost;
import core.domain.post.repository.PostRepositoryCustom;
import core.domain.user.entity.QBlockUser;
import core.domain.user.entity.QUser;
import core.global.enums.BoardCategory;
import core.global.enums.ImageType;
import core.global.enums.LikeType;
import core.global.image.entity.QImage;
import core.global.like.entity.QLike;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Repository
@RequiredArgsConstructor
public class PostRepositoryImpl implements PostRepositoryCustom {
    private static final QPost post = QPost.post;
    private static final QUser user = QUser.user;
    private static final QLike like = QLike.like;
    private static final QBoard board = QBoard.board;
    private static final QComment comment = QComment.comment;
    private static final ImageType IMAGE_TYPE_POST = ImageType.POST;
    private static final ImageType IMAGE_TYPE_USER = ImageType.USER;
    private static final LikeType LIKE_TYPE_POST = LikeType.POST;

    private final JPAQueryFactory query;

    @Override
    public List<BoardItem> findLatestPosts(Long userId, Long boardId,
                                           Instant cursorCreatedAt,
                                           Long cursorId,
                                           int size,
                                           String q) {

        BooleanExpression boardFilter = (boardId == null) ? null : post.board.id.eq(boardId);
        BooleanExpression search = (q == null || q.isBlank())
                ? null
                : post.content.containsIgnoreCase(q);

        BooleanExpression ltCursor = (cursorCreatedAt == null)
                ? null
                : post.createdAt.lt(cursorCreatedAt)
                .or(post.createdAt.eq(cursorCreatedAt)
                        .and(cursorId != null ? post.id.lt(cursorId) : Expressions.TRUE.isFalse())
                );

        Expression<String> authorNameExpr = getAuthorName();

        Expression<String> preview = preview200();

        Expression<Long> likeCountExpr = likeCountExpr();

        Expression<Long> commentCountExpr = commentCountExpr();

        Expression<Boolean> likedByMe = likedByViewerId(userId);

        QImage u = new QImage("u");
        Expression<String> userImageUrlExpr =
                JPAExpressions
                        .select(u.url)
                        .from(u)
                        .where(
                                u.imageType.eq(IMAGE_TYPE_USER)
                                        .and(u.relatedId.eq(user.id))
                        );

        Expression<String> contentThumbnailUrlExpr = firstPostImageUrlExpr();

        BooleanExpression visibleToMe = visibleTo(userId);

        return query
                .select(Projections.constructor(
                        BoardItem.class,
                        post.id,
                        preview,
                        authorNameExpr,
                        board.category,
                        post.createdAt,
                        likedByMe,
                        likeCountExpr,
                        commentCountExpr,
                        post.checkCount,
                        userImageUrlExpr,
                        contentThumbnailUrlExpr,
                        postImageCountExpr(),
                        Expressions.numberTemplate(Long.class, "NULL")
                ))
                .from(post)
                .join(post.author, user)
                .join(post.board, board)
                .where(allOf(boardFilter, search, ltCursor, visibleToMe))
                .orderBy(post.createdAt.desc())
                .limit(Math.min(size, 50) + 1L)
                .fetch();
    }

    @Override
    public List<BoardItem> findPopularPosts(Long userId, Long boardId, Instant since, Long cursorScore, Long cursorId, int size, String q) {
        // ── 필터
        BooleanExpression boardFilter = (boardId == null) ? null : post.board.id.eq(boardId);
        BooleanExpression sinceFilter = (since == null) ? null : post.createdAt.goe(since);
        BooleanExpression search = (q == null || q.isBlank()) ? null : post.content.containsIgnoreCase(q);

        // ── 집계
        Expression<Long> likeCountSub = likeCountExpr();

        Expression<Long> commentCountSub = commentCountExpr();

        NumberExpression<Long> likes = Expressions.numberTemplate(Long.class, "({0})", likeCountSub);
        NumberExpression<Long> comments = Expressions.numberTemplate(Long.class, "({0})", commentCountSub);
        NumberExpression<Long> views = post.checkCount;

        // ── 최신성(나이 시간)
        NumberExpression<Double> ageHours =
                Expressions.numberTemplate(
                        Double.class,
                        "(extract(epoch from current_timestamp) - extract(epoch from {0})) / 3600.0",
                        post.createdAt
                );

        NumberExpression<Long> recencyPoints =
                Expressions.numberTemplate(
                        Long.class,
                        "cast(round(1000 * exp(-(({0}) / 24.0)), 0) as long)",
                        ageHours
                );

        // ── 최종 점수(정수)
        NumberExpression<Long> score =
                recencyPoints.multiply(3L)
                        .add(views.multiply(1L))
                        .add(likes.multiply(3L))
                        .add(comments.multiply(3L));

        // ── 커서 조건(무한스크롤)
        BooleanExpression ltCursor = null;
        if (cursorScore != null) {
            BooleanExpression tieBreaker =
                    (cursorId != null)
                            ? post.id.lt(cursorId)
                            : Expressions.FALSE;

            ltCursor = score.lt(cursorScore)
                    .or(score.eq(cursorScore).and(tieBreaker));
        }

        Expression<Boolean> likedByMe = likedByViewerId(userId);

        Expression<String> authorNameExpr = getAuthorName();

        Expression<String> preview = preview200();

        // Images
        QImage u = new QImage("u");
        Expression<String> userImageUrlExpr =
                JPAExpressions
                        .select(u.url)
                        .from(u)
                        .where(u.imageType.eq(IMAGE_TYPE_USER)
                                .and(u.relatedId.eq(user.id)));

        Expression<String> contentThumbnailUrlExpr = firstPostImageUrlExpr();

        BooleanExpression visibleToMe = visibleTo(userId);

        return query
                .select(Projections.constructor(
                        BoardItem.class,
                        post.id,
                        preview,
                        authorNameExpr,
                        board.category,
                        post.createdAt,
                        likedByMe,
                        likes,
                        comments,
                        views,
                        userImageUrlExpr,
                        contentThumbnailUrlExpr,
                        postImageCountExpr(),
                        score
                ))
                .from(post)
                .join(post.author, user)
                .join(post.board, board)
                .where(allOf(boardFilter, sinceFilter, search, ltCursor, visibleToMe))
                .orderBy(
                        score.desc(),
                        post.id.desc()
                )
                .limit(Math.min(size, 50) + 1L)
                .fetch();

    }

    @Override
    public PostDetailResponse findPostDetail(String email, Long postId) {
        QImage userImage = new QImage("u");

        Expression<Long> likeCountExpr = likeCountExpr();
        Expression<Long> commentCountExpr = commentCountExpr();
        StringExpression userNameExpr = getAuthorName();

        Expression<String> userImageUrlExpr = new CaseBuilder()
                .when(post.anonymous.isTrue()).then(Expressions.nullExpression(String.class))
                .otherwise(
                        JPAExpressions.select(userImage.url)
                                .from(userImage)
                                .where(
                                        userImage.imageType.eq(IMAGE_TYPE_USER)
                                                .and(userImage.relatedId.eq(user.id))
                                )
                );

        QImage image = QImage.image;
        QImage image2 = new QImage("image2");

        Expression<Integer> imageCountExpr =
                JPAExpressions.select(image2.id.count().intValue())
                        .from(image2)
                        .where(
                                image2.imageType.eq(IMAGE_TYPE_POST)
                                        .and(image2.relatedId.eq(post.id))
                        );

        Expression<String> linkExpr = Expressions.constant("CHAT LINK");

        Expression<Boolean> likedByMe = likedByViewerEmail(email);

        List<Tuple> rows = query
                .select(
                        post.id,
                        post.content,
                        userNameExpr,
                        board.category,
                        post.createdAt,
                        linkExpr,
                        likedByMe,
                        likeCountExpr,
                        commentCountExpr,
                        post.checkCount,
                        userImageUrlExpr,
                        image.url,
                        imageCountExpr
                )
                .from(post)
                .join(post.author, user)
                .join(post.board, board)
                .leftJoin(image).on(
                        image.imageType.eq(IMAGE_TYPE_POST)
                                .and(image.relatedId.eq(post.id))
                )
                .where(post.id.eq(postId))
                .orderBy(image.id.asc())
                .fetch();

        if (rows.isEmpty()) {
            return null;
        }

        // 첫 행에서 단건 필드 뽑기
        Tuple t0 = rows.get(0);

        Long id = t0.get(post.id);
        String content = t0.get(post.content);
        String userName = t0.get(userNameExpr);
        BoardCategory cat = t0.get(board.category);
        Instant createdAt = t0.get(post.createdAt);
        String link = t0.get(linkExpr);
        Boolean liked = t0.get(likedByMe);
        Long likeCount = t0.get(likeCountExpr);
        Long commentCount = t0.get(commentCountExpr);
        Long viewCount = t0.get(post.checkCount);
        String userImageUrl = t0.get(userImageUrlExpr);
        Integer imageCount = t0.get(imageCountExpr);

        List<String> contentImageUrls = rows.stream()
                .map(r -> r.get(image.url))
                .filter(Objects::nonNull)
                .toList();

        return new PostDetailResponse(
                id,
                content,
                userName,
                cat,
                createdAt,
                link,
                liked,
                likeCount,
                commentCount,
                viewCount,
                userImageUrl,
                contentImageUrls,
                imageCount
        );
    }

    @Override
    public List<UserPostItem> findMyPostsFirstByEmail(String email, int limitPlusOne) {

        Expression<Boolean> likedByMe = likedByViewerEmail(email);

        return query
                .select(Projections.constructor(
                        UserPostItem.class,
                        post.id,
                        preview200(),
                        post.createdAt,
                        likedByMe,
                        likeCountExpr(),
                        commentCountExpr(),
                        post.checkCount,
                        firstPostImageUrlExpr(),
                        postImageCountExpr()
                ))
                .from(post)
                .join(post.author, user)
                .where(user.email.eq(email))
                .orderBy(post.createdAt.desc(), post.id.desc())
                .limit(limitPlusOne)
                .fetch();
    }

    @Override
    public List<UserPostItem> findMyPostsNextByEmail(String email, Instant cursorCreatedAt, Long cursorId, int limitPlusOne) {
        BooleanExpression ltCursor = post.createdAt.lt(cursorCreatedAt)
                .or(post.createdAt.eq(cursorCreatedAt).and(post.id.lt(cursorId)));

        Expression<Boolean> likedByMe = likedByViewerEmail(email);

        return query
                .select(Projections.constructor(
                        UserPostItem.class,
                        post.id,
                        preview200(),
                        post.createdAt,
                        likedByMe,
                        likeCountExpr(),
                        commentCountExpr(),
                        post.checkCount,
                        firstPostImageUrlExpr(),
                        postImageCountExpr()
                ))
                .from(post)
                .join(post.author, user)
                .where(user.email.eq(email).and(ltCursor))
                .orderBy(post.createdAt.desc(), post.id.desc())
                .limit(limitPlusOne)
                .fetch();
    }

    @Override
    public List<BoardItem> findPostsByIdsForSearch(Long viewerId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();

        Expression<String> preview = preview200();
        Expression<Long> likeCountExpr = likeCountExpr();
        Expression<Long> commentCountExpr = commentCountExpr();
        Expression<Boolean> likedByMe = likedByViewerId(viewerId);
        BooleanExpression visibleToMe = visibleTo(viewerId);

        QImage uimg = new QImage("uimg");
        Expression<String> userImageUrlExpr =
                JPAExpressions.select(uimg.url)
                        .from(uimg)
                        .where(uimg.imageType.eq(IMAGE_TYPE_USER)
                                .and(uimg.relatedId.eq(user.id)));

        Expression<String> contentThumbUrlExpr = firstPostImageUrlExpr();

        return query
                .select(Projections.constructor(
                        BoardItem.class,
                        post.id,
                        preview,
                        makeGetName(),
                        board.category,
                        post.createdAt,
                        likedByMe,
                        likeCountExpr,
                        commentCountExpr,
                        post.checkCount,
                        userImageUrlExpr,
                        contentThumbUrlExpr,
                        postImageCountExpr(),
                        Expressions.numberTemplate(Long.class, "NULL")
                ))
                .from(post)
                .join(post.author, user)
                .join(post.board, board)
                .where(post.id.in(ids).and(visibleToMe))
                .fetch();
    }

    private StringExpression makeGetName() {
        return user.lastName.coalesce("")
                .concat(" ")
                .concat(user.firstName.coalesce(""));
    }

    private BooleanExpression visibleTo(Long userId) {
        if (userId == null) return null; // 비로그인

        QBlockUser bu = QBlockUser.blockUser;

        BooleanExpression notMyBlocking = JPAExpressions
                .selectOne()
                .from(bu)
                .where(bu.user.id.eq(userId)
                        .and(bu.blocked.id.eq(user.id))
                )
                .notExists();

        BooleanExpression notTheirBlocking = JPAExpressions
                .selectOne()
                .from(bu)
                .where(bu.user.id.eq(user.id)
                        .and(bu.blocked.id.eq(userId))
                )
                .notExists();

        return notMyBlocking.and(notTheirBlocking);
    }

    private Expression<Integer> postImageCountExpr() {
        QImage i = new QImage("i");
        return JPAExpressions
                .select(i.id.countDistinct().intValue())
                .from(i)
                .where(
                        i.imageType.eq(IMAGE_TYPE_POST)
                                .and(i.relatedId.eq(post.id))
                );
    }


    private StringExpression getAuthorName() {
        return new CaseBuilder()
                .when(post.anonymous.isTrue()).then("Anonymity")
                .otherwise(makeGetName());
    }

    private Expression<String> preview200() {
        return Expressions.stringTemplate("substring({0}, 1, 200)", post.content);
    }

    private Expression<String> firstPostImageUrlExpr() {
        QImage pi1 = new QImage("pi1");
        QImage pi2 = new QImage("pi2");

        return JPAExpressions
                .select(pi2.url)
                .from(pi2)
                .where(
                        pi2.imageType.eq(IMAGE_TYPE_POST),
                        pi2.relatedId.eq(post.id),
                        pi2.id.eq(
                                JPAExpressions
                                        .select(pi1.id.min())
                                        .from(pi1)
                                        .where(
                                                pi1.imageType.eq(IMAGE_TYPE_POST),
                                                pi1.relatedId.eq(post.id)
                                        )
                        )
                );
    }

    private Expression<Long> commentCountExpr() {
        return JPAExpressions.select(comment.count())
                .from(comment)
                .where(comment.post.eq(post));
    }

    private Expression<Long> likeCountExpr() {
        return JPAExpressions.select(like.count())
                .from(like)
                .where(like.type.eq(LIKE_TYPE_POST)
                        .and(like.relatedId.eq(post.id)));
    }

    // 🔹 viewerId(로그인 유저 id)로 좋아요 여부
    private Expression<Boolean> likedByViewerId(Long viewerId) {
        if (viewerId == null) return Expressions.FALSE; // 비로그인
        return JPAExpressions
                .selectOne()
                .from(like)
                .where(
                        like.type.eq(LIKE_TYPE_POST)
                                .and(like.relatedId.eq(post.id))
                                .and(like.user.id.eq(viewerId))
                )
                .exists();
    }

    // 🔹 viewerEmail(로그인 유저 email)로 좋아요 여부 (내 게시글 목록 API에서 사용)
    private Expression<Boolean> likedByViewerEmail(String email) {
        if (email == null || email.isBlank()) return Expressions.FALSE;
        return JPAExpressions
                .selectOne()
                .from(like)
                .where(
                        like.type.eq(LIKE_TYPE_POST)
                                .and(like.relatedId.eq(post.id))
                                .and(like.user.email.eq(email))
                )
                .exists();
    }


    private BooleanExpression allOf(BooleanExpression... exps) {
        BooleanExpression result = null;
        for (BooleanExpression e : exps) {
            if (e == null) continue;
            result = (result == null) ? e : result.and(e);
        }
        return result;
    }
}
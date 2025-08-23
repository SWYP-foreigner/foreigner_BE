//package core.domain.post.repository.impl;
//
//import com.querydsl.core.types.Expression;
//import com.querydsl.core.types.Projections;
//import com.querydsl.core.types.dsl.*;
//import com.querydsl.jpa.JPAExpressions;
//import com.querydsl.jpa.impl.JPAQueryFactory;
//import core.domain.board.dto.BoardItem;
//import core.domain.board.entity.QBoard;
//import core.domain.comment.entity.QComment;
//import core.domain.post.dto.PostDetailResponse;
//import core.domain.post.dto.UserPostItem;
//import core.domain.post.entity.QPost;
//import core.domain.post.repository.PostRepositoryCustom;
//import core.domain.user.entity.QUser;
//import core.global.enums.LikeType;
//import core.global.enums.ImageType;
//import core.global.image.entity.QImage;
//import core.global.like.entity.QLike;
//import lombok.RequiredArgsConstructor;
//import org.springframework.stereotype.Repository;
//
//import java.time.Instant;
//import java.util.List;
//import java.util.Map;
//
//import static com.querydsl.core.group.GroupBy.groupBy;
//import static com.querydsl.core.types.Projections.list;
//
//@Repository
//@RequiredArgsConstructor
//public class PostRepositoryImpl implements PostRepositoryCustom {
//    private static final QPost post = QPost.post;
//    private static final QUser user = QUser.user;
//    private static final QLike like = QLike.like;
//    private static final QBoard board = QBoard.board;
//    private static final QComment comment = QComment.comment;
//    private static final QImage image = QImage.image;
//    private static final ImageType IMAGE_TYPE_POST = ImageType.POST;
//    private static final ImageType IMAGE_TYPE_USER = ImageType.USER;
//    private static final LikeType LIKE_TYPE_POST = LikeType.POST;
//
//    private final JPAQueryFactory query;
//
//    @Override
//    public List<BoardItem> findLatestPosts(Long boardId,
//                                           Instant cursorCreatedAt,
//                                           Long cursorId,
//                                           int size,
//                                           String q) {
//
//        BooleanExpression boardFilter = (boardId == null) ? null : post.board.id.eq(boardId);
//        BooleanExpression search = (q == null || q.isBlank())
//                ? null
//                : post.content.containsIgnoreCase(q);
//
//        BooleanExpression ltCursor = (cursorCreatedAt == null)
//                ? null
//                : post.createdAt.lt(cursorCreatedAt)
//                .or(post.createdAt.eq(cursorCreatedAt)
//                        .and(cursorId != null ? post.id.lt(cursorId) : Expressions.TRUE.isFalse())
//                );
//
//        Expression<String> authorNameExpr = getAuthorName();
//
//        Expression<String> preview = preview200();
//
//        Expression<Long> likeCountExpr = likeCountExpr();
//
//        Expression<Long> commentCountExpr = commentCountExpr();
//
//        QImage u = new QImage("u");
//        Expression<String> userImageUrlExpr =
//                JPAExpressions
//                        .select(u.url)
//                        .from(u)
//                        .where(
//                                u.imageType.eq(IMAGE_TYPE_USER)
//                                        .and(u.relatedId.eq(user.id))
//                        );
//
//        Expression<String> contentThumbnailUrlExpr = firstPostImageUrlExpr();
//
//        return query
//                .select(Projections.constructor(
//                        BoardItem.class,
//                        post.id,
//                        preview,
//                        authorNameExpr,
//                        board.category,
//                        post.createdAt,
//                        likeCountExpr,
//                        commentCountExpr,
//                        post.checkCount,
//                        userImageUrlExpr,
//                        contentThumbnailUrlExpr,
//                        Expressions.numberTemplate(Long.class, "NULL")
//                ))
//                .from(post)
//                .join(post.author, user)
//                .join(post.board, board)
//                .where(allOf(boardFilter, search, ltCursor))
//                .orderBy(post.createdAt.desc())
//                .limit(Math.min(size, 50) + 1L)
//                .fetch();
//    }
//
//    private BooleanExpression allOf(BooleanExpression... exps) {
//        BooleanExpression result = null;
//        for (BooleanExpression e : exps) {
//            if (e == null) continue;
//            result = (result == null) ? e : result.and(e);
//        }
//        return result;
//    }
//
//    @Override
//    public List<BoardItem> findPopularPosts(Long boardId, Instant since, Long cursorScore, Long cursorId, int size, String q) {
//        // ── 필터
//        BooleanExpression boardFilter = (boardId == null) ? null : post.board.id.eq(boardId);
//        BooleanExpression sinceFilter = (since == null) ? null : post.createdAt.goe(since);
//        BooleanExpression search = (q == null || q.isBlank()) ? null : post.content.containsIgnoreCase(q);
//
//        // ── 집계
//        Expression<Long> likeCountSub = likeCountExpr();
//
//        Expression<Long> commentCountSub = commentCountExpr();
//
//        NumberExpression<Long> likes = Expressions.numberTemplate(Long.class, "({0})", likeCountSub);
//        NumberExpression<Long> comments = Expressions.numberTemplate(Long.class, "({0})", commentCountSub);
//        NumberExpression<Long> views = post.checkCount;
//
//        // ── 최신성(나이 시간)
//        NumberExpression<Double> ageHours =
//                Expressions.numberTemplate(Double.class,
//                        "EXTRACT(EPOCH FROM (NOW() - {0})) / 3600.0", post.createdAt);
//
//        NumberExpression<Double> recencyFactor =
//                Expressions.numberTemplate(Double.class, "EXP(-({0} / 24.0))", ageHours);
//
//        NumberExpression<Long> recencyPoints =
//                Expressions.numberTemplate(Long.class, "CAST(ROUND(1000 * {0}, 0) AS BIGINT)", recencyFactor);
//
//        // ── 최종 점수(정수)
//        NumberExpression<Long> score =
//                recencyPoints.multiply(5L)
//                        .add(views.multiply(3L))
//                        .add(likes)
//                        .add(comments);
//
//        // ── 커서 조건(무한스크롤)
//        BooleanExpression ltCursor = null;
//        if (cursorScore != null) {
//            BooleanExpression tieBreaker =
//                    (cursorId != null)
//                            ? post.id.lt(cursorId)
//                            : Expressions.FALSE;
//
//            ltCursor = score.lt(cursorScore)
//                    .or(score.eq(cursorScore).and(tieBreaker));
//        }
//
//        Expression<String> authorNameExpr = getAuthorName();
//
//        Expression<String> preview = preview200();
//
//        // Images
//        QImage u = new QImage("u");
//        Expression<String> userImageUrlExpr =
//                JPAExpressions
//                        .select(u.url)
//                        .from(u)
//                        .where(u.imageType.eq(IMAGE_TYPE_USER)
//                                .and(u.relatedId.eq(user.id)));
//
//        Expression<String> contentThumbnailUrlExpr = firstPostImageUrlExpr();
//
//        return query
//                .select(Projections.constructor(
//                        BoardItem.class,
//                        post.id,
//                        preview,
//                        authorNameExpr,
//                        board.category,
//                        post.createdAt,
//                        likes,
//                        comments,
//                        views,
//                        userImageUrlExpr,
//                        contentThumbnailUrlExpr,
//                        score
//                ))
//                .from(post)
//                .join(post.author, user)
//                .join(post.board, board)
//                .where(allOf(boardFilter, sinceFilter, search, ltCursor))
//                .orderBy(
//                        score.desc(),
//                        post.id.desc()
//                )
//                .limit(Math.min(size, 50) + 1L)
//                .fetch();
//
//    }
//
//    @Override
//    public PostDetailResponse findPostDetail(Long postId) {
//        QImage userImage = new QImage("u");
//
//        Expression<Long> likeCountExpr = likeCountExpr();
//        Expression<Long> commentCountExpr = commentCountExpr();
//        StringExpression userNameExpr = getAuthorName();
//
//        Expression<String> userImageUrlExpr = new CaseBuilder()
//                .when(post.anonymous.isTrue()).then(Expressions.nullExpression(String.class))
//                .otherwise(
//                        JPAExpressions.select(userImage.url)
//                                .from(userImage)
//                                .where(
//                                        userImage.imageType.eq(IMAGE_TYPE_USER)
//                                                .and(userImage.relatedId.eq(user.id))
//                                )
//                );
//
//        Map<Long, PostDetailResponse> result = query
//                .from(post)
//                .join(post.author, user)
//                .join(post.board, board)
//                .leftJoin(image).on(
//                        image.imageType.eq(IMAGE_TYPE_POST)
//                                .and(image.relatedId.eq(post.id))
//                )
//                .where(post.id.eq(postId))
//                .orderBy(image.id.asc())
//                .transform(groupBy(post.id).as(
//                        // 그룹별 DTO 조립
//                        Projections.constructor(PostDetailResponse.class,
//                                post.id,
//                                post.content,
//                                userNameExpr,
//                                post.createdAt,
//                                likeCountExpr,
//                                commentCountExpr,
//                                post.checkCount,
//                                userImageUrlExpr,
//                                list(image.url),
//                                board.category
//                        )
//                ));
//
//        return result.get(postId);
//    }
//
//    @Override
//    public List<UserPostItem> findMyPostsFirstByName(String name, int limitPlusOne) {
//        return query
//                .select(Projections.constructor(
//                        UserPostItem.class,
//                        preview200(),
//                        post.createdAt,
//                        likeCountExpr(),
//                        commentCountExpr(),
//                        post.checkCount,
//                        firstPostImageUrlExpr()
//                ))
//                .from(post)
//                .join(post.author, user)
//                .where(user.name.eq(name))
//                .orderBy(post.createdAt.desc(), post.id.desc())
//                .limit(limitPlusOne)
//                .fetch();
//    }
//
//    @Override
//    public List<UserPostItem> findMyPostsNextByName(String name, Instant cursorCreatedAt, Long cursorId, int limitPlusOne) {
//        BooleanExpression ltCursor = post.createdAt.lt(cursorCreatedAt)
//                .or(post.createdAt.eq(cursorCreatedAt).and(post.id.lt(cursorId)));
//
//        return query
//                .select(Projections.constructor(
//                        UserPostItem.class,
//                        preview200(),
//                        post.createdAt,
//                        likeCountExpr(),
//                        commentCountExpr(),
//                        post.checkCount,
//                        firstPostImageUrlExpr()
//                ))
//                .from(post)
//                .join(post.author, user)
//                .where(user.name.eq(name).and(ltCursor))
//                .orderBy(post.createdAt.desc(), post.id.desc())
//                .limit(limitPlusOne)
//                .fetch();
//    }
//
//
//    private StringExpression getAuthorName() {
//        return new CaseBuilder()
//                .when(post.anonymous.isTrue()).then("익명")
//                .otherwise(user.name);
//    }
//
//    private Expression<String> preview200() {
//        return Expressions.stringTemplate("substring({0}, 1, 200)", post.content);
//    }
//
//    private Expression<String> firstPostImageUrlExpr() {
//        QImage pi1 = new QImage("pi1");
//        QImage pi2 = new QImage("pi2");
//
//        return JPAExpressions
//                .select(pi2.url)
//                .from(pi2)
//                .where(pi2.id.eq(
//                        JPAExpressions
//                                .select(pi1.id.min())
//                                .from(pi1)
//                                .where(
//                                        pi1.imageType.eq(IMAGE_TYPE_POST)
//                                                .and(pi1.relatedId.eq(post.id))
//                                )
//                ));
//    }
//
//    private Expression<Long> commentCountExpr() {
//        return JPAExpressions.select(comment.count())
//                .from(comment)
//                .where(comment.post.eq(post));
//    }
//
//    private Expression<Long> likeCountExpr() {
//        return JPAExpressions.select(like.count())
//                .from(like)
//                .where(like.type.eq(LIKE_TYPE_POST)
//                        .and(like.relatedId.eq(post.id)));
//    }
//}
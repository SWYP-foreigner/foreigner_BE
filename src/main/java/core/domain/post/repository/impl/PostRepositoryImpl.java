package core.domain.post.repository.impl;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.*;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import core.domain.board.dto.BoardResponse;
import core.domain.comment.entity.QComment;
import core.domain.post.dto.PostDetailIntermediate;
import core.domain.post.dto.PostDetailResponse;
import core.domain.post.entity.QPost;
import core.domain.post.repository.PostRepositoryCustom;
import core.domain.user.entity.QUser;
import core.global.enums.LikeType;
import core.global.image.entity.ImageType;
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
    private static final QComment comment = QComment.comment;
    private static final QImage image = QImage.image;
    private static final ImageType IMAGE_TYPE_POST = ImageType.POST;
    private static final ImageType IMAGE_TYPE_USER = ImageType.USER;
    private static final LikeType LIKE_TYPE_POST = LikeType.POST;

    private final JPAQueryFactory query;

    @Override
    public List<BoardResponse> findLatestPosts(Long boardId,
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

        Expression<String> authorNameExpr =
                new CaseBuilder()
                        .when(post.anonymous.isTrue()).then("익명")
                        .otherwise(user.name);

        Expression<String> preview =
                Expressions.stringTemplate("substring({0}, 1, 200)", post.content);

        Expression<Long> likeCountExpr =
                JPAExpressions.select(like.count())
                        .from(like)
                        .where(like.type.eq(LIKE_TYPE_POST)
                                .and(like.relatedId.eq(post.id)));

        Expression<Long> commentCountExpr =
                JPAExpressions.select(comment.count())
                        .from(comment)
                        .where(comment.post.eq(post));

        QImage u = new QImage("u");
        Expression<String> userImageUrlExpr =
                JPAExpressions
                        .select(u.url)
                        .from(u)
                        .where(
                                u.imageType.eq(IMAGE_TYPE_USER)
                                        .and(u.relatedId.eq(user.id))
                        );

        QImage pi1 = new QImage("pi1");
        QImage pi2 = new QImage("pi2");
        Expression<String> contentThumbnailUrlExpr =
                JPAExpressions
                        .select(pi2.url)
                        .from(pi2)
                        .where(pi2.id.eq(
                                JPAExpressions
                                        .select(pi1.id.min())
                                        .from(pi1)
                                        .where(
                                                pi1.imageType.eq(IMAGE_TYPE_POST)
                                                        .and(pi1.relatedId.eq(post.id))
                                        )
                        ));

        return query
                .select(Projections.constructor(
                        BoardResponse.class,
                        post.id,
                        preview,
                        authorNameExpr,
                        post.createdAt,
                        likeCountExpr,
                        commentCountExpr,
                        post.checkCount,
                        userImageUrlExpr,
                        contentThumbnailUrlExpr,
                        Expressions.numberTemplate(Long.class, "NULL")
                ))
                .from(post)
                .join(post.author, user)
                .where(allOf(boardFilter, search, ltCursor))
                .orderBy(post.createdAt.desc())
                .limit(Math.min(size, 50) + 1L)
                .fetch();
    }

    private BooleanExpression allOf(BooleanExpression... exps) {
        BooleanExpression result = null;
        for (BooleanExpression e : exps) {
            if (e == null) continue;
            result = (result == null) ? e : result.and(e);
        }
        return result;
    }

    @Override
    public List<BoardResponse> findPopularPosts(Long boardId, Instant since, Long cursorScore, Long cursorId, int size, String q) {
        // ── 필터
        BooleanExpression boardFilter = (boardId == null) ? null : post.board.id.eq(boardId);
        BooleanExpression sinceFilter = (since == null) ? null : post.createdAt.goe(since);
        BooleanExpression search = (q == null || q.isBlank()) ? null : post.content.containsIgnoreCase(q);

        // ── 집계
        Expression<Long> likeCountSub =
                JPAExpressions.select(like.count())
                        .from(like)
                        .where(like.type.eq(LIKE_TYPE_POST)
                                .and(like.relatedId.eq(post.id)));

        Expression<Long> commentCountSub =
                JPAExpressions.select(comment.count())
                        .from(comment)
                        .where(comment.post.eq(post));

        NumberExpression<Long> likes = Expressions.numberTemplate(Long.class, "({0})", likeCountSub);
        NumberExpression<Long> comments = Expressions.numberTemplate(Long.class, "({0})", commentCountSub);
        NumberExpression<Long> views = post.checkCount;

        // ── 최신성(나이 시간)
        NumberExpression<Double> ageHours =
                Expressions.numberTemplate(Double.class,
                        "EXTRACT(EPOCH FROM (NOW() - {0})) / 3600.0", post.createdAt);

        NumberExpression<Double> recencyFactor =
                Expressions.numberTemplate(Double.class, "EXP(-({0} / 24.0))", ageHours);

        NumberExpression<Long> recencyPoints =
                Expressions.numberTemplate(Long.class, "CAST(ROUND(1000 * {0}, 0) AS BIGINT)", recencyFactor);

        // ── 최종 점수(정수)
        NumberExpression<Long> score =
                recencyPoints.multiply(5L)
                        .add(views.multiply(3L))
                        .add(likes)
                        .add(comments);

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

        Expression<String> authorNameExpr =
                new CaseBuilder()
                        .when(post.anonymous.isTrue()).then("익명")
                        .otherwise(user.name);

        Expression<String> preview =
                Expressions.stringTemplate("substring({0}, 1, 200)", post.content);


        // Images
        QImage u = new QImage("u");
        Expression<String> userImageUrlExpr =
                JPAExpressions
                        .select(u.url)
                        .from(u)
                        .where(u.imageType.eq(IMAGE_TYPE_USER)
                                .and(u.relatedId.eq(user.id)));

        QImage pi1 = new QImage("pi1");
        QImage pi2 = new QImage("pi2");
        Expression<String> contentThumbnailUrlExpr =
                JPAExpressions
                        .select(pi2.url)
                        .from(pi2)
                        .where(pi2.id.eq(
                                JPAExpressions
                                        .select(pi1.id.min())
                                        .from(pi1)
                                        .where(pi1.imageType.eq(IMAGE_TYPE_POST)
                                                .and(pi1.relatedId.eq(post.id)))
                        ));

        return query
                .select(Projections.constructor(
                        BoardResponse.class,
                        post.id,
                        preview,
                        authorNameExpr,
                        post.createdAt,
                        likes,
                        comments,
                        views,
                        userImageUrlExpr,
                        contentThumbnailUrlExpr,
                        score
                ))
                .from(post)
                .join(post.author, user)
                .where(allOf(boardFilter, sinceFilter, search, ltCursor))
                .orderBy(
                        score.desc(),
                        post.id.desc()
                )
                .limit(Math.min(size, 50) + 1L)
                .fetch();

    }

    @Override
    public PostDetailResponse findPostDetail(Long postId) {
        QImage u = new QImage("u");

        // 1) 필요한 Expression 정의 (서브쿼리 포함)
        Expression<Long> likeCountExpr = JPAExpressions.select(like.count())
                .from(like)
                .where(like.type.eq(LIKE_TYPE_POST)
                        .and(like.relatedId.eq(post.id)));

        Expression<Long> commentCountExpr = JPAExpressions.select(comment.count())
                .from(comment)
                .where(comment.post.eq(post));

        StringExpression userNameExpr = new CaseBuilder()
                .when(post.anonymous.isTrue()).then("익명")
                .otherwise(user.name);

        Expression<String> userImageUrlExpr = new CaseBuilder()
                .when(post.anonymous.isTrue()).then(Expressions.nullExpression(String.class))
                .otherwise(
                        JPAExpressions.select(u.url)
                                .from(u)
                                .where(u.imageType.eq(IMAGE_TYPE_USER)
                                        .and(u.relatedId.eq(user.id)))
                );

        // 2) 한 번에 평평한 결과로 가져오기 (이미지 조인으로 행이 늘어날 수 있음)
        List<Tuple> rows = query
                .select(
                        post.id,
                        post.content,
                        userNameExpr,
                        post.createdAt,
                        likeCountExpr,
                        commentCountExpr,
                        post.checkCount,
                        userImageUrlExpr,
                        image.url
                )
                .from(post)
                .join(post.author, user)
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

        // 3) 스칼라 필드는 첫 행에서 추출
        Tuple t0 = rows.getFirst();
        Long              id           = t0.get(post.id);
        String            content      = t0.get(post.content);
        String            userName     = t0.get(userNameExpr);
        java.time.Instant createdTime  = t0.get(post.createdAt);
        Long              likeCount    = t0.get(likeCountExpr);
        Long              commentCount = t0.get(commentCountExpr);
        Long              viewCount    = t0.get(post.checkCount);
        String            userImageUrl = t0.get(userImageUrlExpr);

        // 4) 이미지 URL은 자바에서 수집(정렬 유지 + 중복 제거)
        List<String> contentImageUrls = rows.stream()
                .map(r -> r.get(image.url))
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        return new PostDetailResponse(
                id,
                content,
                userName,
                createdTime,
                likeCount,
                commentCount,
                viewCount,
                userImageUrl,
                contentImageUrls
        );
    }
}
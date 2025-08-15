package core.domain.post.repository.impl;

import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
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
import java.util.Map;

import static com.querydsl.core.group.GroupBy.groupBy;
import static com.querydsl.core.types.Projections.list;

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
                        preview,
                        authorNameExpr,
                        post.createdAt,
                        likeCountExpr,
                        commentCountExpr,
                        post.checkCount,
                        userImageUrlExpr,
                        contentThumbnailUrlExpr
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
        throw new UnsupportedOperationException("TODO: score(or 가중치) 기반 구현");
    }

    @Override
    public PostDetailResponse findPostDetail(Long postId) {
        QImage u = new QImage("u");

        Map<Long, PostDetailIntermediate> rows = query
                .from(post)
                .join(post.author, user)
                .leftJoin(image).on(
                        image.imageType.eq(IMAGE_TYPE_POST)
                                .and(image.relatedId.eq(post.id))
                )
                .where(post.id.eq(postId))
                .orderBy(image.id.asc())
                .transform(
                        groupBy(post.id).as(
                                Projections.constructor(
                                        PostDetailIntermediate.class,
                                        post.content,
                                        new CaseBuilder().when(post.anonymous.isTrue()).then("익명")
                                                .otherwise(user.name),
                                        post.createdAt,
                                        JPAExpressions.select(like.count())
                                                .from(like)
                                                .where(like.type.eq(LIKE_TYPE_POST)
                                                        .and(like.relatedId.eq(post.id))),
                                        JPAExpressions.select(comment.count())
                                                .from(comment)
                                                .where(comment.post.eq(post)),
                                        post.checkCount,
                                        new CaseBuilder()
                                                .when(post.anonymous.isTrue()).then((String) null)
                                                .otherwise(
                                                        JPAExpressions.select(u.url)
                                                                .from(u)
                                                                .where(u.imageType.eq(IMAGE_TYPE_USER)
                                                                        .and(u.relatedId.eq(user.id)))
                                                ),
                                        list(image.url)
                                )
                        )
                );

        PostDetailIntermediate r = rows.get(postId);
        if (r == null) {
            return null;
        }

        return new PostDetailResponse(
                r.content(),
                r.userName(),
                r.createdTime(),
                r.likeCount(),
                r.commentCount(),
                r.viewCount(),
                r.userImageUrl(),
                r.contentImageUrls()
        );
    }
}
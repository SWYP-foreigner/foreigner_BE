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
import core.domain.post.entity.QPost;
import core.domain.post.repository.PostRepositoryCustom;
import core.domain.user.entity.QUser;
import core.global.image.entity.QImage;
import core.global.like.entity.QLike;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class PostRepositoryImpl implements PostRepositoryCustom {
    private static final QPost post = QPost.post;
    private static final QUser user = QUser.user;
    private static final QLike like = QLike.like;
    private static final QComment comment = QComment.comment;
    private static final QImage image = QImage.image;
    private static final String TYPE_POST = "POST";
    private final JPAQueryFactory query;

    @Override
    public List<BoardResponse> findLatestPosts(Long boardId,
                                               Instant cursorCreatedAt,
                                               Long cursorId,
                                               int size,
                                               String q) {

        // 동적 조건
        BooleanExpression boardFilter = (boardId == null) ? null : post.board.id.eq(boardId);
        BooleanExpression search = (q == null || q.isBlank())
                ? null
                : post.title.containsIgnoreCase(q)
                .or(post.content.containsIgnoreCase(q));

        BooleanExpression ltCursor = (cursorCreatedAt == null)
                ? null
                : post.createdAt.lt(cursorCreatedAt)
                .or(post.createdAt.eq(cursorCreatedAt).and(post.id.lt(cursorId)));

        // authorName 익명 마스킹
        Expression<String> authorNameExpr =
                new CaseBuilder()
                        .when(post.anonymous.isTrue()).then("익명")
                        .otherwise(user.name);

        Expression<String> preview =
                Expressions.stringTemplate("substring({0}, 1, 200)", post.content);

        Expression<Long> likeCountExpr =
                JPAExpressions.select(like.count())
                        .from(like)
                        .where(like.type.eq(TYPE_POST)
                                .and(like.relatedId.eq(post.id)));

        Expression<Long> commentCountExpr =
                JPAExpressions.select(comment.count())
                        .from(comment)
                        .where(comment.post.eq(post));

        Expression<Long> firstImageId =
                JPAExpressions
                        .select(image.id.min())
                        .from(image)
                        .where(image.imageType.eq(TYPE_POST)
                                .and(image.relatedId.eq(post.id)));

        Expression<String> thumbnailExpr =
                JPAExpressions
                        .select(image.url)
                        .from(image)
                        .where(image.id.eq(firstImageId));

        // 메인 쿼리
        // size+1
        return query
                .select(Projections.constructor(
                        BoardResponse.class,
                        post.title,
                        preview,
                        authorNameExpr,
                        post.createdAt,
                        likeCountExpr,
                        commentCountExpr,
                        post.checkCount,
                        thumbnailExpr
                ))
                .from(post)
                .join(post.author, user)
                .where(allOf(boardFilter, search, ltCursor))
                .orderBy(post.createdAt.desc())
                .limit(Math.min(size, 50) + 1L) // size+1
                .fetch();
    }

    // 가독성 보조: null 무시 and 결합
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
}
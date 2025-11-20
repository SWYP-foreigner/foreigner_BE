package core.domain.comment.repository;

import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.*;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.JPQLQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import core.domain.comment.dto.CommentListResponse;
import core.domain.comment.dto.CommentSearchRequest;
import core.domain.comment.entity.Comment;
import core.domain.comment.entity.QComment;
import core.domain.post.entity.QPost;
import core.domain.user.entity.QBlockUser;
import core.domain.user.entity.QUser;
import core.global.entity.like.entity.QLike;
import core.global.enums.common.LikeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static core.domain.comment.entity.QComment.comment;
import static core.domain.user.entity.QBlockUser.blockUser;
import static core.domain.user.entity.QUser.user;

@RequiredArgsConstructor
public class CommentRepositoryCustomImpl implements CommentRepositoryCustom {

    private final JPAQueryFactory query;

    private static final QComment c = comment;
    private static final QUser u = new QUser("u");
    private static final QPost p = QPost.post;
    private static final QBlockUser bu1 = new QBlockUser("bu1");
    private static final QBlockUser bu2 = new QBlockUser("bu2");
    private static final QLike l = QLike.like;

    // ───────────────────────── 최신 ─────────────────────────

    @Override
    public Slice<Comment> findByPostId(Long userId, Long postId, Pageable pageable) {
        List<Comment> rows = query
                .selectFrom(c)
                .join(c.author, u).fetchJoin()
                .join(c.post, p).fetchJoin()
                .where(
                        p.id.eq(postId)
                                .and(visibleTo(userId))
                )
                .orderBy(c.createdAt.desc(), c.id.desc())
                .limit(pageable.getPageSize() + 1L)
                .fetch();

        return toSlice(rows, pageable);
    }

    @Override
    public Slice<Comment> findCommentByCursor(
            Long userId, Long postId, Instant cursorCreatedAt, Long cursorId, Pageable pageable
    ) {
        BooleanExpression ltCursor = c.createdAt.lt(cursorCreatedAt)
                .or(c.createdAt.eq(cursorCreatedAt)
                        .and(cursorId != null ? c.id.lt(cursorId) : Expressions.FALSE));

        List<Comment> rows = query
                .selectFrom(c)
                .join(c.author, u).fetchJoin()
                .join(c.post, p).fetchJoin()
                .where(
                        p.id.eq(postId)
                                .and(ltCursor)
                                .and(visibleTo(userId))
                )
                .orderBy(c.createdAt.desc(), c.id.desc())
                .limit(pageable.getPageSize() + 1L)
                .fetch();

        return toSlice(rows, pageable);
    }

    // ───────────────────────── 인기(좋아요 desc, createdAt desc, id desc) ─────────────────────────
    @Override
    public Slice<Comment> findPopularByPostId(
            Long userId, Long postId, LikeType type, Pageable pageable
    ) {
        NumberExpression<Long> lc = likeCount(type);

        List<Comment> rows = query
                .selectFrom(c)
                .join(c.author, u).fetchJoin()
                .join(c.post, p).fetchJoin()
                .where(
                        p.id.eq(postId)
                                .and(visibleTo(userId))
                )
                .orderBy(lc.desc(), c.createdAt.desc(), c.id.desc())
                .limit(pageable.getPageSize() + 1L)
                .fetch();

        return toSlice(rows, pageable);
    }

    @Override
    public Slice<Comment> findPopularByCursor(
            Long userId,
            Long postId,
            LikeType type,
            Long cursorLikeCount,
            Instant cursorCreatedAt,
            Long cursorId,
            Pageable pageable
    ) {
        NumberExpression<Long> lc = likeCount(type);

        // 커서: (좋아요수 desc, createdAt desc, id desc)
        BooleanExpression ltCursor =
                lc.lt(cursorLikeCount)
                        .or(
                                lc.eq(cursorLikeCount)
                                        .and(
                                                c.createdAt.lt(cursorCreatedAt)
                                                        .or(
                                                                c.createdAt.eq(cursorCreatedAt)
                                                                        .and(cursorId != null ? c.id.lt(cursorId) : Expressions.FALSE)
                                                        )
                                        )
                        );

        List<Comment> rows = query
                .selectFrom(c)
                .join(c.author, u).fetchJoin()
                .join(c.post, p).fetchJoin()
                .where(
                        p.id.eq(postId)
                                .and(visibleTo(userId))
                                .and(ltCursor)
                )
                .orderBy(lc.desc(), c.createdAt.desc(), c.id.desc())
                .limit(pageable.getPageSize() + 1L)
                .fetch();

        return toSlice(rows, pageable);
    }

    @Override
    public Page<CommentListResponse> searchComments(CommentSearchRequest condition, Pageable pageable) {

        JPQLQuery<Long> reportCountSubQuery = JPAExpressions.select(blockUser.count())
                .from(blockUser)
                .where(blockUser.blocked.id.eq(comment.author.id));

        List<CommentListResponse> content = query
                .select(Projections.constructor(CommentListResponse.class,
                        comment.id,
                        comment.post.id,
                        Expressions.stringTemplate("concat({0}, ' ', {1})", user.firstName, user.lastName),
                        user.email,
                        comment.content,
                        comment.createdAt,
                        reportCountSubQuery,
                        comment.deleted
                ))
                .from(comment)
                .join(comment.author, user)
                .where(
                        authorEmailContains(condition.authorEmail()),
                        authorNameContains(condition.authorName()),
                        contentContains(condition.content()),
                        createdAtBetween(condition.startDate(), condition.endDate()),
                        onlyReported(condition.onlyReported(), reportCountSubQuery)
                )
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .orderBy(getOrderSpecifiers(pageable.getSort()).toArray(OrderSpecifier[]::new))
                .fetch();

        long total = query.select(comment.id).from(comment).join(comment.author, user)
                .where(
                        authorEmailContains(condition.authorEmail()),
                        contentContains(condition.content()),
                        createdAtBetween(condition.startDate(), condition.endDate()),
                        onlyReported(condition.onlyReported(), reportCountSubQuery)
                )
                .fetchCount();

        return new PageImpl<>(content, pageable, total);
    }

    private List<OrderSpecifier> getOrderSpecifiers(Sort sort) {
        List<OrderSpecifier> orders = new ArrayList<>();
        if (sort != null && !sort.isEmpty()) {
            sort.forEach(order -> {
                Order direction = order.isAscending() ? Order.ASC : Order.DESC;
                PathBuilder<Comment> pathBuilder = new PathBuilder<>(Comment.class, "comment");

                switch (order.getProperty()) {
                    case "authorName":
                        StringExpression fullName = Expressions.stringTemplate("concat({0}, ' ', {1})", user.firstName, user.lastName);
                        orders.add(new OrderSpecifier<>(direction, fullName));
                        break;
                    case "reportCount":
                        orders.add(new OrderSpecifier<>(direction,
                                JPAExpressions.select(blockUser.count())
                                        .from(blockUser)
                                        .where(blockUser.blocked.id.eq(comment.author.id))
                        ));
                        break;
                    default:
                        orders.add(new OrderSpecifier(direction, pathBuilder.get(order.getProperty(), Comparable.class)));
                        break;
                }
            });
        }

        orders.add(new OrderSpecifier(Order.DESC, comment.id));
        return orders;
    }

    private BooleanExpression onlyReported(Boolean onlyReported, JPQLQuery<Long> subQuery) {
        if (onlyReported == null || !onlyReported) return null;
        return subQuery.gt(0L);
    }

    private BooleanExpression authorEmailContains(String email) {
        return StringUtils.hasText(email) ? user.email.containsIgnoreCase(email) : null;
    }

    private BooleanExpression contentContains(String content) {
        return StringUtils.hasText(content) ? comment.content.containsIgnoreCase(content) : null;
    }

    private BooleanExpression createdAtBetween(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            return null;
        }
        return comment.createdAt.between(
                startDate.atStartOfDay().atZone(ZoneId.systemDefault()).toInstant(),
                endDate.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant()
        );
    }

    private BooleanExpression authorNameContains(String name) {
        if (!StringUtils.hasText(name)) return null;

        StringExpression fullName = Expressions.stringTemplate("concat({0}, ' ', {1})", user.firstName, user.lastName);
        return fullName.containsIgnoreCase(name);
    }
    /** 차단(양방향) 필터: userId가 null이면 필터 비활성화 */
    private BooleanExpression visibleTo(Long userId) {
        if (userId == null) return null;
        BooleanExpression notMyBlocking = JPAExpressions.selectOne().from(bu1)
                .where(bu1.user.id.eq(userId)
                        .and(bu1.blocked.id.eq(u.id)))
                .notExists();

        BooleanExpression notTheirBlocking = JPAExpressions.selectOne().from(bu2)
                .where(bu2.user.id.eq(u.id)
                        .and(bu2.blocked.id.eq(userId)))
                .notExists();

        return notMyBlocking.and(notTheirBlocking);
    }

    /** 인기 정렬용 좋아요 수 서브쿼리 */
    private NumberExpression<Long> likeCount(LikeType type) {
        return Expressions.numberTemplate(
                Long.class,
                "({0})",
                JPAExpressions.select(l.id.count())
                        .from(l)
                        .where(l.type.eq(type).and(l.relatedId.eq(c.id)))
        );
    }
    private <T> Slice<T> toSlice(List<T> rows, Pageable pageable) {
        boolean hasNext = rows.size() > pageable.getPageSize();
        if (hasNext) rows.remove(rows.size() - 1);
        return new SliceImpl<>(rows, pageable, hasNext);
    }

}
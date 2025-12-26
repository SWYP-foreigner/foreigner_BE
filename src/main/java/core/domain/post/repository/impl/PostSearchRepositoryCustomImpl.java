package core.domain.post.repository.impl;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import core.domain.comment.entity.QComment;
import core.domain.post.dto.search.SearchResultView;
import core.domain.post.entity.QPost;
import core.domain.post.repository.PostSearchRepositoryCustom;
import core.domain.post.dto.search.PostSearchRequest;
import core.domain.user.entity.QUser;
import core.global.entity.image.entity.QImage;
import core.global.entity.like.entity.QLike;
import core.global.enums.ImageType;
import core.global.enums.LikeType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

import static core.domain.bookmark.entity.QBookmark.bookmark;
import static core.domain.post.entity.QPost.post;
import static core.global.entity.like.entity.QLike.like;

@Repository
@RequiredArgsConstructor
public class PostSearchRepositoryCustomImpl implements PostSearchRepositoryCustom {

    private final static LikeType LIKE_TYPE_POST = LikeType.POST;


    private final JPAQueryFactory jpaQueryFactory;

    @PersistenceContext
    private final EntityManager entityManager;

    /**
     * 정확도 정렬 1종 + 키셋 커서(score, created_at, post_id) 역순
     * - boardId == null 이면 전체
     * - afterTime/afterId 없으면 첫 페이지
     * <p>
     * PGroonga 기준:
     * - WHERE: p.content &@~ query
     * - 점수 : pgroonga.score(tableoid, ctid)
     * - 쿼리 escape: pgroonga.query_escape(q)
     */
    @Override
    public List<SearchResultView> search(PostSearchRequest request) {
        QPost p = post;
        QUser user = QUser.user;
        QImage uimg = new QImage("uimg_ps");   // 프로필 이미지
        QImage pi1 = new QImage("pi1_ps");    // 첫 본문 이미지 id min
        QImage pi2 = new QImage("pi2_ps");    // 첫 본문 이미지 url
        QImage pic = new QImage("pic_ps");    // 이미지 개수
        QComment c = QComment.comment;        // 댓글
        QLike l = like;                       // 좋아요

        // --- PGroonga 매치/점수(Double) ---
        var match = Expressions.booleanTemplate(
                "function('pgroonga_match', {0}, {1}) = true",
                p.content, Expressions.constant(request.q()) // request에서 추출
        );
        NumberExpression<Double> score = Expressions.numberTemplate(
                Double.class,
                "function('pgroonga_score_of', {0})",
                p.id
        );

        // WHERE
        var where = new BooleanBuilder().and(match);
        if (request.boardId() != null) {
            where.and(p.board.id.eq(request.boardId()));
        }
        if (request.blockedIds() != null && !request.blockedIds().isEmpty()) {
            where.and(p.author.id.notIn(request.blockedIds()));
        }

        // ---- 키셋 커서 (객체에서 추출하여 비교) ----
        if (request.afterScore() != null && request.afterTime() != null && request.afterId() != null) {
            where.and(
                    score.lt(request.afterScore())
                            .or(score.eq(request.afterScore()).and(p.createdAt.lt(request.afterTime())))
                            .or(score.eq(request.afterScore()).and(p.createdAt.eq(request.afterTime())).and(p.id.lt(request.afterId())))
            );
        }

        // 미리보기 200자
        StringExpression preview200 =
                Expressions.stringTemplate("function('left', {0}, 200)", p.content);

        // 익명 보호 authorName/authorId
        StringExpression authorNameExpr = new CaseBuilder()
                .when(p.anonymous.isTrue()).then("Anonymity")
                .otherwise(
                        p.author.lastName.coalesce("")
                                .concat(" ")
                                .concat(p.author.firstName.coalesce(""))
                );

        Expression<Long> authorIdExpr = new CaseBuilder()
                .when(p.anonymous.isTrue()).then(Expressions.nullExpression(Long.class))
                .otherwise(p.author.id);

        // 프로필 이미지 URL
        Expression<String> userImageUrlExpr = new CaseBuilder()
                .when(p.anonymous.isTrue()).then(Expressions.nullExpression(String.class))
                .otherwise(
                        JPAExpressions.select(uimg.url)
                                .from(uimg)
                                .where(uimg.imageType.eq(ImageType.USER).and(uimg.relatedId.eq(p.author.id)))
                );

        // 본문 첫 이미지 URL
        Expression<String> contentThumbUrlExpr =
                JPAExpressions.select(pi2.url)
                        .from(pi2)
                        .where(
                                pi2.imageType.eq(ImageType.POST),
                                pi2.relatedId.eq(p.id),
                                pi2.id.eq(
                                        JPAExpressions.select(pi1.id.min())
                                                .from(pi1)
                                                .where(pi1.imageType.eq(ImageType.POST), pi1.relatedId.eq(p.id))
                                )
                        );

        // 이미지 개수
        Expression<Integer> imageCountExpr =
                JPAExpressions.select(pic.id.countDistinct().intValue())
                        .from(pic)
                        .where(pic.imageType.eq(ImageType.POST).and(pic.relatedId.eq(p.id)));

        // 집계 및 좋아요 여부
        Expression<Long> likeCountExpr =
                JPAExpressions.select(l.count())
                        .from(l)
                        .where(l.type.eq(LikeType.POST).and(l.relatedId.eq(p.id)));

        // request에서 userId 추출
        Expression<Boolean> likedByMe = likedByViewerId(request.userId());

        Expression<Long> commentCountExpr =
                JPAExpressions.select(c.count())
                        .from(c)
                        .where(c.post.eq(p));

        // 반올림 점수
        NumberExpression<Long> scoreRounded =
                Expressions.numberTemplate(Long.class, "cast(round({0}, 0) as long)", score);

        return jpaQueryFactory
                .select(Projections.constructor(SearchResultView.class,
                        Projections.constructor(core.domain.board.dto.BoardItem.class,
                                p.id, preview200, authorIdExpr, authorNameExpr,
                                p.board.category, p.createdAt, p.anonymous, likedByMe,
                                bookmarkedByViewerId(request.userId()), // request에서 추출
                                likeCountExpr, commentCountExpr, p.checkCount,
                                userImageUrlExpr, contentThumbUrlExpr, imageCountExpr,
                                scoreRounded
                        ),
                        score
                ))
                .from(p)
                .where(where)
                .orderBy(score.desc(), p.createdAt.desc(), p.id.desc())
                .limit(request.limit()) // request에서 추출
                .fetch();
    }

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

    @Override
    @SuppressWarnings("unchecked")
    public List<String> findHotKeywordsOrTitles(int topN) {
        String sql = """
                WITH docs AS (
                  SELECT p.post_id AS doc_id, p.post_content
                  FROM public.post p
                  WHERE p.created_at >= now() - interval '90 days'
                ),
                tokens AS (
                  SELECT d.doc_id, w.term
                  FROM docs d
                  CROSS JOIN LATERAL unnest(
                    pgroonga_tokenize(
                      d.post_content,
                      'tokenizer','TokenDelimit'     -- ★ 단어 단위 토크나이저
                    )
                  ) AS t(token_json)
                  /* term을 LATERAL 서브셀렉트에서 컬럼으로 만든다 */
                  CROSS JOIN LATERAL (
                    SELECT lower(btrim((t.token_json::jsonb ->> 'value'))) AS term
                  ) AS w
                  WHERE w.term <> ''
                    AND w.term !~ '\\s'               -- 공백 포함 토큰 제거
                    AND length(w.term) BETWEEN 2 AND 20
                    AND w.term !~ '^[0-9]+$'
                    AND w.term !~ '^(https?://|www\\\\.)'
                    AND w.term !~ '^[[:punct:]]+$'
                )
                SELECT term
                FROM tokens
                GROUP BY term
                HAVING COUNT(DISTINCT doc_id) >= 1   -- 문서 수 기준 빈도
                ORDER BY COUNT(DISTINCT doc_id) DESC
                LIMIT :topN
                """;


        return entityManager.createNativeQuery(sql)
                .setParameter("topN", topN)
                .getResultList();
    }


    /**
     * 자동완성:
     * - prefix: pgroonga.query_escape(q) || '*'
     * - 중복 스니펫 제거: GROUP BY snippet
     * - 정렬: max(score) desc, max(created_at) desc
     */
    @Override
    public List<String> suggest(String q, Long boardId, List<Long> blockedIds, int limit) {
        QPost p = post;

        // 접두어 매칭: 래퍼가 escape + '*'까지 처리
        var match = Expressions.booleanTemplate(
                "function('pgroonga_match_prefix', {0}, {1}) = true",
                p.content, Expressions.constant(q)
        );

        var where = new BooleanBuilder().and(match);
        if (boardId != null) where.and(p.board.id.eq(boardId));
        if (blockedIds != null && !blockedIds.isEmpty()) where.and(p.author.id.notIn(blockedIds));

        // 중복 제거 키: 140자 스니펫
        var snippet140 = Expressions.stringTemplate("left({0}, 140)", p.content);

        // 점수/최신일 집계 (tableoid/ctid 쓰지 말고 래퍼 사용)
        NumberExpression<Double> maxScore = Expressions.numberTemplate(
                Double.class, "max(function('pgroonga_score_of', {0}))", p.id
        );
        var maxCreatedAt = Expressions.dateTimeTemplate(Instant.class, "max({0})", p.createdAt);

        return jpaQueryFactory.select(snippet140)
                .from(p)
                .where(where)
                .groupBy(snippet140)
                .orderBy(maxScore.desc(), maxCreatedAt.desc())
                .limit(limit)
                .fetch();
    }


    private Expression<Boolean> bookmarkedByViewerId(Long viewerId) {
        if (viewerId == null) return Expressions.FALSE; // 비로그인
        return JPAExpressions
                .selectOne()
                .from(bookmark)
                .where(
                        bookmark.user.id.eq(viewerId)
                )

                .exists();
    }
}
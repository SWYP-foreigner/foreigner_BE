package core.domain.post.repository.impl;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import core.domain.post.dto.search.PostSearchProjection;
import core.domain.post.entity.QPost;
import core.domain.post.repository.PostSearchRepositoryCustom;
import core.domain.post.dto.search.PostSearchRequest;
import core.global.enums.LikeType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

import static core.domain.post.entity.QPost.post;

@Repository
@RequiredArgsConstructor
public class PostSearchRepositoryCustomImpl implements PostSearchRepositoryCustom {

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
    public List<PostSearchProjection> search(PostSearchRequest request) {
        QPost p = post;

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
        NumberExpression<Long> scoreRounded = Expressions.numberTemplate(Long.class,
                "cast(round({0}, 0) as long)", score);

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
                    scoreRounded.lt(request.afterScore().longValue())
                            .or(scoreRounded.eq(request.afterScore().longValue()).and(p.createdAt.lt(request.afterTime())))
                            .or(scoreRounded.eq(request.afterScore().longValue()).and(p.createdAt.eq(request.afterTime())).and(p.id.lt(request.afterId())))
            );
        }

        return jpaQueryFactory
                .select(Projections.constructor(PostSearchProjection.class,
                        p.id,
                        Expressions.stringTemplate("left({0}, 200)", p.content),
                        new CaseBuilder().when(p.anonymous.isTrue()).then(Expressions.nullExpression(Long.class)).otherwise(p.author.id),
                        new CaseBuilder().when(p.anonymous.isTrue()).then("Anonymity").otherwise(p.author.lastName.concat(" ").concat(p.author.firstName)),
                        p.board.category,
                        p.createdAt,
                        p.anonymous,
                        p.checkCount,
                        score,
                        scoreRounded
                ))
                .from(p)
                .where(where)
                .orderBy(scoreRounded.desc(), p.createdAt.desc(), p.id.desc())
                .limit(request.limit() + 1L)
                .fetch();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Object[]> findHotKeywordsOrTitles(int topN) {
        String sql = """
            WITH docs AS (
              SELECT p.post_id AS doc_id, p.post_content
              FROM public.post p
              WHERE p.created_at >= now() - interval '7 days'
            ),
            tokens AS (
              SELECT d.doc_id, lower(btrim((t.token_json::jsonb ->> 'value'))) AS term
              FROM docs d
              CROSS JOIN LATERAL unnest(
                pgroonga_tokenize(d.post_content, 'tokenizer', 'TokenDelimit')
              ) AS t(token_json)
              WHERE (t.token_json::jsonb ->> 'value') <> ''
                AND (t.token_json::jsonb ->> 'value') !~ '\\s'
                AND length(t.token_json::jsonb ->> 'value') BETWEEN 2 AND 20
                AND (t.token_json::jsonb ->> 'value') !~ '^[0-9]+$'
                AND (t.token_json::jsonb ->> 'value') !~ '^(https?://|www\\\\.)'
                AND (t.token_json::jsonb ->> 'value') !~ '^[[:punct:]]+$'
            )
            SELECT term, COUNT(DISTINCT doc_id) as freq
            FROM tokens
            GROUP BY term
            HAVING COUNT(DISTINCT doc_id) >= 1
            ORDER BY freq DESC
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


}
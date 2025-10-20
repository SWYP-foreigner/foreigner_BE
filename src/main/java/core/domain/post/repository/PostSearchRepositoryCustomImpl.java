package core.domain.post.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import core.domain.post.dto.SearchResultView;
import core.domain.post.entity.QPost;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

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
    public List<SearchResultView> search(String q, Long boardId, List<Long> blockedIds,
                                         Instant afterTime, Long afterId, int limit) {
        QPost p = QPost.post;

        // --- PGroonga 쿼리/점수 템플릿 ---
        var match = Expressions.booleanTemplate(
                "function('pgroonga_match', {0}, {1}) = true",
                p.content, Expressions.constant(q)
        );

        // 점수 (Double)
        NumberExpression<Double> score = Expressions.numberTemplate(
                Double.class,
                "function('pgroonga_score_of', {0})",
                p.id
        );
        // WHERE
        var where = new BooleanBuilder().and(match);
        if (boardId != null) where.and(p.board.id.eq(boardId));
        if (blockedIds != null && !blockedIds.isEmpty()) where.and(p.author.id.notIn(blockedIds));

        // ---- 키셋 커서 ----
        if (afterTime != null && afterId != null) {
            // 현재 커서 행의 score 서브쿼리
            NumberExpression<Double> curScore = Expressions.numberTemplate(
                    Double.class,
                    "function('pgroonga_score_of', {0})",
                    Expressions.constant(afterId)
            );

            where.and(
                    score.lt(curScore)
                            .or(score.eq(curScore).and(p.createdAt.lt(afterTime)))
                            .or(score.eq(curScore).and(p.createdAt.eq(afterTime)).and(p.id.lt(afterId)))
            );
        }

        // 미리보기 텍스트 200자
        var preview200 = Expressions.stringTemplate("function('left', {0}, 200)", p.content);

        return jpaQueryFactory
                .select(Projections.constructor(SearchResultView.class,
                        Projections.constructor(core.domain.board.dto.BoardItem.class,
                                p.id,                         // postId
                                preview200,                   // contentPreview
                                Expressions.nullExpression(String.class), // content 등 필요 없으면 null
                                p.board.category,
                                p.createdAt,
                                Expressions.constant(false),  // isSomething(ex: bookmarked) 없으면 기본값
                                Expressions.constant(0L),     // likeCount etc 필요 시 교체
                                Expressions.constant(0L),
                                p.checkCount,
                                Expressions.nullExpression(String.class),
                                Expressions.nullExpression(String.class),
                                Expressions.nullExpression(Integer.class),
                                p.checkCount
                        ),
                        score // SearchResultView(item, score)
                ))
                .from(p)
                .where(where)
                .orderBy(score.desc(), p.createdAt.desc(), p.id.desc())
                .limit(limit)
                .fetch();
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
        QPost p = QPost.post;

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
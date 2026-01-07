package core.domain.maincontent.repository.search;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import core.domain.maincontent.dto.MainContentSearchProjection;
import core.domain.maincontent.dto.MainPageSearchRequest;
import core.domain.maincontent.entity.QMainContent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;


@Repository
@RequiredArgsConstructor
public class MainContentSearchRepositoryImpl implements MainContentSearchRepository {
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
    public List<MainContentSearchProjection> search(MainPageSearchRequest request) {
        QMainContent mainPageContent = QMainContent.mainContent;

        // 1. Title 매치
        var matchTitle = Expressions.booleanTemplate(
                "function('pgroonga_match', {0}, {1}) = true",
                mainPageContent.title, Expressions.constant(request.q())
        );

        // 2. HtmlContent 매치
        var matchHtml = Expressions.booleanTemplate(
                "function('pgroonga_match', function('clean_html_for_search', {0}), {1}) = true",
                mainPageContent.htmlContent, Expressions.constant(request.q())
        );

        NumberExpression<Double> score = Expressions.numberTemplate(
                Double.class,
                "function('pgroonga_score_of', {0})",
                mainPageContent.id
        );
        NumberExpression<Long> scoreRounded = Expressions.numberTemplate(Long.class,
                "cast(round({0}, 0) as long)", score);

        // WHERE
        var where = new BooleanBuilder().and(matchTitle.or(matchHtml));

        // ---- 키셋 커서 (객체에서 추출하여 비교) ----
        if (request.afterScore() != null && request.afterTime() != null && request.afterId() != null) {
            where.and(
                    scoreRounded.lt(request.afterScore().longValue())
                            .or(scoreRounded.eq(request.afterScore().longValue())
                                    .and(mainPageContent.createdAt.lt(request.afterTime())))
                            .or(scoreRounded.eq(request.afterScore().longValue())
                                    .and(mainPageContent.createdAt.eq(request.afterTime()))
                                    .and(mainPageContent.id.lt(request.afterId())))
            );
        }

        return jpaQueryFactory
                .select(Projections.constructor(MainContentSearchProjection.class,
                        mainPageContent.id,
                        Expressions.stringTemplate("left({0}, 200)", mainPageContent.title),
                        mainPageContent.type,
                        mainPageContent.createdAt,
                        score,
                        scoreRounded
                ))
                .from(mainPageContent)
                .where(where)
                .orderBy(scoreRounded.desc(), mainPageContent.createdAt.desc(), mainPageContent.id.desc())
                .limit(request.limit() + 1)
                .fetch();
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<Object[]> findHotKeywordsOrTitles(int topN) {
        String sql = """
                WITH docs AS (
                  SELECT m.content_id AS doc_id, m.title -- 본문보다 제목에서 키워드 추출이 더 정확함
                  FROM main_page_content m
                  WHERE m.created_at >= now() - interval '14 days' -- 뉴스는 기간을 조금 더 넓게 잡아도 됨
                ),
                tokens AS (
                  SELECT d.doc_id, lower(btrim((t.token_json::jsonb ->> 'value'))) AS term
                  FROM docs d
                  CROSS JOIN LATERAL unnest(
                    pgroonga_tokenize(d.title, 'tokenizer', 'TokenDelimit')
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
    public List<String> suggest(String q, int limit) {
        QMainContent mainPageContent = QMainContent.mainContent;

        // 1. 제목 접두어 매칭
        var matchTitle = Expressions.booleanTemplate(
                "function('pgroonga_match_prefix', {0}, {1}) = true",
                mainPageContent.title, Expressions.constant(q)
        );

        // 2. 정제된 본문 접두어 매칭 (함수 사용)
        var matchContent = Expressions.booleanTemplate(
                "function('pgroonga_match_prefix', function('clean_html_for_search', {0}), {1}) = true",
                mainPageContent.htmlContent, Expressions.constant(q)
        );

        // 검색 제안으로 보여줄 문자열
        var suggestTarget = mainPageContent.title;

        // 점수 및 최신순 정렬을 위한 집계
        NumberExpression<Double> maxScore = Expressions.numberTemplate(
                Double.class, "max(function('pgroonga_score_of', {0}))", mainPageContent.id
        );
        var maxCreatedAt = Expressions.dateTimeTemplate(java.time.Instant.class, "max({0})", mainPageContent.createdAt);

        return jpaQueryFactory
                .select(suggestTarget)
                .from(mainPageContent)
                .where(matchTitle.or(matchContent))
                .groupBy(suggestTarget)
                .orderBy(maxScore.desc(), maxCreatedAt.desc())
                .limit(limit)
                .fetch();
    }

    /**
     * 추천 검색어 칩용 (Specific Entities): BTS, Blackpink, RM 등 고유 명사 위주 추출
     * - 대소문자를 보존하여 'bts'가 아닌 'BTS' 형태 유지
     * - 영문 대문자 시작 또는 2~5자의 한글 패턴 필터링
     */
    @SuppressWarnings("unchecked")
    @Override
    public List<Object[]> findEntitiesForChips(int topN) {
        String sql = """
                SELECT term, COUNT(DISTINCT doc_id) as freq
                FROM (
                    SELECT m.content_id AS doc_id, 
                           btrim((t.token_json::jsonb ->> 'value')) AS term
                    FROM main_page_content m
                    CROSS JOIN LATERAL unnest(
                        pgroonga_tokenize(m.title, 'tokenizer', 'TokenDelimit')
                    ) AS t(token_json)
                    WHERE m.created_at >= now() - interval '30 days'
                      AND (t.token_json::jsonb ->> 'value') <> ''
                      AND (t.token_json::jsonb ->> 'value') ~ '^[A-Z]{1,}[a-zA-Z]*$|^[가-힣]{2,5}$'
                      AND (t.token_json::jsonb ->> 'value') !~ '^[0-9]+$' -- 숫자만 있는 것 제외
                      AND (t.token_json::jsonb ->> 'value') !~ '^(https?://|www\\\\.)' -- URL 제외
                ) AS tokens
                GROUP BY term
                HAVING COUNT(DISTINCT doc_id) >= 1
                ORDER BY freq DESC
                LIMIT :topN
                """;

        return entityManager.createNativeQuery(sql)
                .setParameter("topN", topN)
                .getResultList();
    }

    @Override
    public boolean existsInContents(String keyword) {
        String sql = """
            SELECT EXISTS (
                SELECT 1 FROM main_page_content 
                WHERE title &@ :keyword OR html_content &@ :keyword -- 실제 컬럼명으로 수정
                LIMIT 1
            )
            """;
        return (boolean) entityManager.createNativeQuery(sql)
                .setParameter("keyword", keyword)
                .getSingleResult();
    }

}

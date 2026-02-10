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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;


@Repository
@RequiredArgsConstructor
public class MainContentSearchRepositoryImpl implements MainContentSearchRepository {
    private final JPAQueryFactory jpaQueryFactory;
    private final NamedParameterJdbcTemplate jdbcTemplate;

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

        // 2. HtmlContent 매치 (원본 htmlContent + 함수 대신 html_content_norm 사용)
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
                  FROM main_content m
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
        String lowerQ = q.toLowerCase().trim();
        List<Object> args = new ArrayList<>();

        // 제목/본문 x (0, 1, 2단어 추가) 총 6개 분기 파라미터
        for (int i = 0; i < 6; i++) { args.add(lowerQ); args.add(lowerQ); }

        StringBuilder subQuery = new StringBuilder("""
            (SELECT pgroonga_extract_phrase(title, ?, 0) as phrase, created_at FROM main_content WHERE title &@* ? LIMIT 200)
            UNION ALL
            (SELECT pgroonga_extract_phrase(title, ?, 1) as phrase, created_at FROM main_content WHERE title &@* ? LIMIT 200)
            UNION ALL
            (SELECT pgroonga_extract_phrase(title, ?, 2) as phrase, created_at FROM main_content WHERE title &@* ? LIMIT 200)
            UNION ALL
            (SELECT pgroonga_extract_phrase(clean_html_for_search(html_content), ?, 0) as phrase, created_at FROM main_content WHERE clean_html_for_search(html_content) &@* ? LIMIT 200)
            UNION ALL
            (SELECT pgroonga_extract_phrase(clean_html_for_search(html_content), ?, 1) as phrase, created_at FROM main_content WHERE clean_html_for_search(html_content) &@* ? LIMIT 200)
            UNION ALL
            (SELECT pgroonga_extract_phrase(clean_html_for_search(html_content), ?, 2) as phrase, created_at FROM main_content WHERE clean_html_for_search(html_content) &@* ? LIMIT 200)
        """);

        String fullSql = String.format("""
            SELECT phrase FROM (%s) AS sub
            WHERE phrase IS NOT NULL AND phrase != ''
            GROUP BY phrase
            ORDER BY 
                MAX(CASE WHEN phrase ILIKE ? THEN 1 ELSE 0 END) DESC, -- 시작 일치 우선
                MIN(LENGTH(phrase)) ASC,                             -- 짧은 것(단어수 적은것) 우선
                MAX(created_at) DESC                                 -- 최신순
            LIMIT ?
        """, subQuery.toString());

        args.add(lowerQ + "%");
        args.add(limit);

        return jdbcTemplate.getJdbcOperations().execute((java.sql.Connection conn) -> {
            try (java.sql.Statement stmt = conn.createStatement()) {
                // 인덱스 스캔 강제
                stmt.execute("SET LOCAL enable_seqscan = off");

                try (java.sql.PreparedStatement pstmt = conn.prepareStatement(fullSql)) {
                    for (int i = 0; i < args.size(); i++) {
                        pstmt.setObject(i + 1, args.get(i));
                    }
                    try (java.sql.ResultSet rs = pstmt.executeQuery()) {
                        List<String> results = new ArrayList<>();
                        while (rs.next()) {
                            results.add(rs.getString(1));
                        }
                        return results;
                    }
                }
            }
        });
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
                    FROM main_content m
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
                SELECT 1 FROM main_content 
                -- 인덱스를 타게 하기 위해 html_content에 함수를 입혀줍니다.
                WHERE title &@ :keyword OR clean_html_for_search(html_content) &@ :keyword 
                LIMIT 1
            )
            """;
        return (boolean) entityManager.createNativeQuery(sql)
                .setParameter("keyword", keyword)
                .getSingleResult();
    }

}

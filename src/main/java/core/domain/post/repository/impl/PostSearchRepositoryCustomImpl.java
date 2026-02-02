package core.domain.post.repository.impl;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import core.domain.poll.entity.QPoll;
import core.domain.post.dto.search.PostSearchProjection;
import core.domain.post.dto.search.PostSearchRequest;
import core.domain.post.entity.QPost;
import core.domain.post.repository.PostSearchRepositoryCustom;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static core.domain.board.entity.QBoard.board;
import static core.domain.post.entity.QPost.post;
import static core.domain.user.entity.QUser.user;

@Repository
@RequiredArgsConstructor
public class PostSearchRepositoryCustomImpl implements PostSearchRepositoryCustom {

    private final JPAQueryFactory jpaQueryFactory;

    @PersistenceContext
    private final EntityManager entityManager;
    private final NamedParameterJdbcTemplate jdbcTemplate;

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
        QPoll poll = QPoll.poll;

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
                        scoreRounded,
                        poll.title,
                        poll.description,
                        poll.closeAt,
                        poll.totalVoteCount.coalesce(0L)
                ))
                .from(p)
                .join(p.author, user)
                .join(p.board, board)
                .leftJoin(p.poll, poll)
                .where(where)
                .orderBy(scoreRounded.desc(), p.createdAt.desc(), p.id.desc())
                .limit(request.limit() + 1L)
                .fetch();
    }

    @Override
    public List<Object[]> findHotKeywordsOrTitles(int topN) {
        QPost p = QPost.post;

        var termPath = Expressions.stringTemplate(
                "btrim(regexp_replace(trim(substring(replace({0}, chr(10), ' '), 1, 40)), '(?i)\\s*''?s\\b', '', 'g'), ' ,.?!')",
                p.content
        );

        // 2. QueryDSL 실행
        List<Tuple> fetch = jpaQueryFactory
                .select(termPath, p.id.count())
                .from(p)
                .where(
                        p.content.length().gt(5)
                                .and(termPath.isNotNull())
                                .and(termPath.ne(""))
                )
                .groupBy(termPath)
                .orderBy(p.id.count().desc())
                .limit(topN)
                .fetch();

        // 3. List<Object[]> 형식으로 변환하여 반환
        return fetch.stream()
                .map(tuple -> new Object[]{tuple.get(0, String.class), tuple.get(1, Long.class)})
                .collect(Collectors.toList());
    }

    @Override
    public List<String> suggest(String q, Long boardId, List<Long> blockedIds, int limit) {
        String lowerQ = q.toLowerCase().trim();
        List<Object> args = new ArrayList<>();

        int[] wordCounts = {0, 1, 2};
        StringBuilder subQuery = new StringBuilder();

        for (int i = 0; i < wordCounts.length; i++) {
            if (i > 0) subQuery.append(" UNION ALL ");

            subQuery.append("(");
            subQuery.append("""
            SELECT 
                pgroonga_extract_phrase(post_content, ?::text, %d) as phrase,
                created_at
            FROM post
            WHERE post_content_norm &@* ?
        """.formatted(wordCounts[i]));

            args.add(lowerQ);
            args.add(lowerQ);

            if (boardId != null) {
                subQuery.append(" AND board_id = ? ");
                args.add(boardId);
            }

            if (blockedIds != null && !blockedIds.isEmpty()) {
                String inSql = blockedIds.stream().map(id -> "?").collect(Collectors.joining(", "));
                subQuery.append(" AND author_id NOT IN (").append(inSql).append(") ");
                args.addAll(blockedIds);
            }
            subQuery.append(" LIMIT 300 "); // 각 분기별로 상위 300개 추출
            subQuery.append(")");
        }

        String fullSql = String.format("""
                SELECT phrase
                FROM (%s) AS sub
                WHERE phrase IS NOT NULL AND phrase != ''
                GROUP BY phrase
                ORDER BY 
                    MAX(CASE WHEN phrase ILIKE ? THEN 1 ELSE 0 END) DESC, -- 입력어 시작 우선
                    MIN(LENGTH(phrase)) ASC,                             -- 짧은 단어(0, 1단어) 우선
                    MAX(created_at) DESC                                 -- 최신순
                LIMIT ?
            """, subQuery.toString());

        args.add(lowerQ + "%");
        args.add(limit);

        return jdbcTemplate.getJdbcOperations().query(
                fullSql,
                (rs, rowNum) -> rs.getString(1),
                args.toArray()
        );
    }
}

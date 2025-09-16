package core.global.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder;
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import core.domain.board.dto.BoardItem;
import core.domain.post.repository.PostRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.UserRepository;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import core.global.search.dto.SearchHitLite;
import core.global.search.dto.SearchResultView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.stream.Collectors;

import static core.global.search.SearchConstants.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostSearchService {

    private static final float PHRASE_BOOST = 3.0f;
    private static final String RECENCY_SCALE = "14d";
    private static final double POP_WEIGHT = 0.4;
    private static final double RECENCY_WEIGHT = 0.6;
    private static final int ABSOLUTE_MAX_SIZE = 10;

    private final ElasticsearchClient es;
    private final BlockRepository blockRepository;
    private final UserRepository userRepository;
    private final PostRepository postRepository;
    private final RecentSearchRedisService redisService;

    public List<SearchResultView> search(String query, Long boardId) {
        // viewer
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Long viewerId = userRepository.findByEmail(email)
                .map(User::getId)
                .orElse(null);

        Long effectiveBoardId = (boardId != null && boardId == 1L) ? null : boardId;

        List<Long> blockedIds = blockRepository.getBlockUsersByUserEmail(email)
                .stream().map(User::getId).toList();


        if (viewerId != null && query != null && !query.isBlank()) {
            redisService.log(viewerId, query);
            log.debug("[SEARCH][RECENT] logged query for user={}, q='{}'", viewerId, query);
        }

        try {
            log.debug("[SEARCH][BEGIN] email={}, viewerId={}, boardId={}, effectiveBoardId={}, blockedCount={}",
                    email, viewerId, boardId, effectiveBoardId, blockedIds.size());


            var hits = searchIdsAndHighlights(query, effectiveBoardId, blockedIds, viewerId);
            if (hits.isEmpty()) {
                log.debug("[SEARCH][END] no hits");
                return List.of();
            }

            var ids = hits.stream().map(SearchHitLite::id).toList();
            log.debug("[ES→APP] hitIds(from ES)={}", ids);

            // ⬇️ JPA는 차단 미적용 수화 전용
            var items = postRepository.findPostsByIdsForSearch(viewerId, ids);
            log.debug("[JPA] fetchedCount={}, fetchedIds={}",
                    items.size(), items.stream().map(BoardItem::postId).toList());

            // ES 순서로 정렬 + 하이라이트/점수 주입
            Map<Long, Integer> order = new HashMap<>();
            for (int i = 0; i < ids.size(); i++) order.put(ids.get(i), i);

            Map<Long, String> hlMap = hits.stream()
                    .collect(Collectors.toMap(SearchHitLite::id, SearchHitLite::highlight, (a, b) -> a, LinkedHashMap::new));
            Map<Long, Double> scMap = hits.stream()
                    .collect(Collectors.toMap(SearchHitLite::id, SearchHitLite::score, (a, b) -> a, LinkedHashMap::new));

            items.sort(Comparator.comparingInt(x -> order.getOrDefault(x.postId(), Integer.MAX_VALUE)));
            log.debug("[JPA] fetched={} ids={}", items.size(),
                    items.stream().map(BoardItem::postId).toList());

            var missingInDb = new LinkedHashSet<>(ids);
            missingInDb.removeAll(items.stream().map(BoardItem::postId).toList());
            if (!missingInDb.isEmpty()) {
                log.warn("[DIFF] presentInES_butMissingInDB={}", missingInDb);
            }

            var result = items.stream()
                    .map(i -> new SearchResultView(i, hlMap.get(i.postId()), scMap.getOrDefault(i.postId(), 0.0)))
                    .toList();


            if (log.isDebugEnabled()) {
                log.debug("[SEARCH][END] returnCount={}, orderedIds={}",
                        result.size(), result.stream().map(r -> r.item().postId()).toList());
            }

            return result;

        } catch (ElasticsearchException e) {
            log.error("[ES] search failed (status={}, reason={})",
                    e.response() != null ? e.response().status() : "n/a",
                    e.error() != null ? e.error().reason() : e.getMessage(), e);
            throw new BusinessException(ErrorCode.ELASTICSEARCH_SEARCH_FAILED);
        } catch (IOException e) {
            log.error("[ES] IO failed: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.ELASTICSEARCH_SEARCH_FAILED);
        } catch (Exception e) {
            log.error("[ES] unexpected: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.ELASTICSEARCH_SEARCH_FAILED);
        }
    }

    /**
     * ES에서 문서 id(=postId), score, highlight만 가져오는 부분
     */
    private List<SearchHitLite> searchIdsAndHighlights(String query,
                                                       Long boardId,
                                                       List<Long> blockedIds,
                                                       Long viewerId) throws IOException {

        log.debug("[ES][SEARCH][REQ] viewerId={}, boardId={}, blockedCount={}, query='{}'",
                viewerId, boardId, blockedIds == null ? 0 : blockedIds.size(), query);

        long t0 = System.currentTimeMillis();

        SearchResponse<Void> resp = es.search(s -> s
                        .index(INDEX_POSTS_SEARCH)
                        .from(0).size(ABSOLUTE_MAX_SIZE)
                        .source(src -> src.filter(f -> f.excludes("*")))
                        .query(q -> q.functionScore(fs -> fs
                                .query(base -> base.bool(b -> {
                                    if (boardId != null) {
                                        b = b.filter(f -> f.term(t -> t.field("boardId").value(boardId)));
                                    }
                                    // 차단 유저 must_not
                                    b = applyBlockFilter(b, blockedIds, viewerId);

                                    // 본문 매칭(phrase → AND → fuzzy)
                                    b = b.should(sh -> sh.matchPhrase(mp -> mp
                                            .field("content").query(query).slop(1).boost(PHRASE_BOOST)));
                                    b = b.should(sh -> sh.match(m -> m
                                            .field("content").query(query).operator(Operator.And)));
                                    b = b.should(sh -> sh.match(m -> m
                                            .field("content").query(query).fuzziness("AUTO")));
                                    b = b.minimumShouldMatch("1");
                                    return b;
                                }))
                                // 인기도(로그 완화)
                                .functions(fn -> fn.fieldValueFactor(f -> f
                                                .field("checkCount").factor(1.0)
                                                .modifier(FieldValueFactorModifier.Log1p).missing(0.0))
                                        .weight(POP_WEIGHT))
                                // 최신성(가우시안)
                                .functions(fn -> fn.gauss(g -> g
                                                .field("createdAt")
                                                .placement(p -> p.origin(JsonData.of("now"))
                                                        .scale(JsonData.of(RECENCY_SCALE))
                                                        .decay(0.5)))
                                        .weight(RECENCY_WEIGHT))
                                .scoreMode(FunctionScoreMode.Multiply)
                                .boostMode(FunctionBoostMode.Multiply)))
                        .sort(ss -> ss.score(o -> o.order(SortOrder.Desc)))
                        .sort(ss -> ss.field(f -> f.field("createdAt").order(SortOrder.Desc)))
                        .highlight(h -> h.preTags("<em>").postTags("</em>")
                                .fields("content", hf -> hf.numberOfFragments(1).fragmentSize(140)))
                        // .explain(true) // (옵션) 잠깐만 켜서 확인
                        .trackTotalHits(t -> t.enabled(true))
                , Void.class);

        long wallMs = System.currentTimeMillis() - t0;

        // === 추가: 응답 요약 ===
        int hitCount = resp.hits().hits().size();
        Long total = resp.hits().total() == null ? null : resp.hits().total().value();
        log.debug("[ES][SEARCH][RESP] took={}ms(es), wall={}ms, hits={}, total={}",
                resp.took(), wallMs, hitCount, total);

        // === 추가: 상위 히트 요약 ===
        for (int i = 0; i < hitCount; i++) {
            var h = resp.hits().hits().get(i);
            String hl = null;
            var hs = h.highlight() == null ? null : h.highlight().get("content");
            if (hs != null && !hs.isEmpty()) hl = hs.get(0);
            log.debug("[ES][HIT][{}] id={}, score={}, hl={}", i, h.id(), h.score(), hl);
        }

        var esIds = resp.hits().hits().stream().map(h -> h.id()).toList();
        log.debug("[ES] ids={}", esIds);

        return resp.hits().hits().stream().map(h -> {
            String hl = null;
            var hs = h.highlight() == null ? null : h.highlight().get("content");
            if (hs != null && !hs.isEmpty()) hl = hs.get(0);
            return new SearchHitLite(Long.parseLong(h.id()),
                    h.score() == null ? 0.0 : h.score(), hl);
        }).toList();
    }

    /**
     * 차단 필터 적용: 소수면 직접 terms, 많으면 terms lookup
     */
    private Builder applyBlockFilter(Builder b,
                                     List<Long> blockedIds, Long viewerId) {

        if (blockedIds == null || blockedIds.isEmpty()) return b;

        boolean useTermsLookup = blockedIds.size() >= BLOCK_TERMS_LOOKUP_THRESHOLD;
        log.debug("[ES][BLOCK] mode={}, blockedCount={}, threshold={}",
                useTermsLookup ? "terms_lookup" : "terms",
                blockedIds.size(), BLOCK_TERMS_LOOKUP_THRESHOLD);

        if (blockedIds.size() < BLOCK_TERMS_LOOKUP_THRESHOLD) {
            var vals = blockedIds.stream().map(FieldValue::of).toList();
            return b.mustNot(mn -> mn.terms(t -> t.field("userId").terms(ts -> ts.value(vals))));
        } else {
            return b.mustNot(mn -> mn.terms(t -> t.field("userId").terms(ts -> ts
                    .lookup(l -> l.index(USER_FILTER_INDEX)
                            .id(String.valueOf(viewerId))
                            .path(USER_FILTER_BLOCKED_PATH)))));
        }
    }

    private static final JsonpMapper __ES_JSON_MAPPER = new JacksonJsonpMapper();

    private static String __toJson(Object obj) {
        try {
            StringWriter sw = new StringWriter();
            var gen = __ES_JSON_MAPPER.jsonProvider().createGenerator(sw);
            __ES_JSON_MAPPER.serialize(obj, gen);
            gen.close();
            return sw.toString();
        } catch (Exception e) {
            return "{\"_logError\":\"" + e.getMessage() + "\"}";
        }
    }

}
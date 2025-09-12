package core.global.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.search.CompletionContext;
import co.elastic.clients.elasticsearch.core.search.Context;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.UserRepository;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import core.global.search.SearchConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static core.global.search.SearchConstants.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostSearchSuggestService {
    private static final int LIMIT = 5;
    private static final int PER_PIPE = 10;
    private final ElasticsearchClient es;
    private final BlockRepository blockRepository;
    private final UserRepository userRepository;

    /**
     * 하이브리드 서제스트: completion(정확/오타) + ASYT(fallback with filters)
     */
    public List<String> suggest(String prefix) {
        return suggest(prefix, null); // 기존 글로벌 제안 유지
    }

    public List<String> suggest(String prefix, Long boardId) {
        final String pfx = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT).trim();
        if (pfx.isEmpty()) return List.of();

        final Long effectiveBoardId = (boardId != null && boardId == 1L) ? null : boardId;
        final boolean useFuzzy = pfx.length() >= 3;


        try {
            String email = SecurityContextHolder.getContext().getAuthentication().getName();
            Long viewerId = userRepository.findByEmail(email).map(User::getId).orElse(null);
            List<Long> blockedIds = blockRepository.getBlockUsersByUserEmail(email)
                    .stream().map(User::getId).toList();

            // === 1) completion: exact + fuzzy (contexts 전달) ===
            // Java ES Client에서 contexts는 SuggestContextQuery 맵으로 전달됩니다.
            final Map<String, List<CompletionContext>> ctx =
                    (effectiveBoardId == null)
                            ? null
                            : Map.of(
                            "boardId",
                            List.of(CompletionContext.of(b ->
                                    b.context(Context.of(c -> c.category(String.valueOf(effectiveBoardId))))
                            ))
                    );

            // 1) completion exact/fuzzy
            var comp = es.search(s -> s
                            .index(INDEX_POSTS_SUGGEST)
                            .size(0)
                            .trackTotalHits(t -> t.enabled(false))
                            .timeout("300ms")
                            .suggest(sug -> sug
                                    .suggesters("exact", s1 -> s1.prefix(pfx).completion(c -> {
                                        var b = c.field("contentSuggestExact").skipDuplicates(true).size(PER_PIPE);
                                        return (ctx != null) ? b.contexts(ctx) : b;
                                    }))
                                    .suggesters("fuzzy", s2 -> s2.prefix(pfx).completion(c -> {
                                        var b = c.field("contentSuggest").skipDuplicates(true).size(PER_PIPE);
                                        if (useFuzzy) b = b.fuzzy(f -> f.fuzziness("AUTO"));
                                        return (ctx != null) ? b.contexts(ctx) : b;
                                    }))
                            ),
                    Map.class);

            var out = new LinkedHashSet<String>();

            var exact = comp.suggest() != null ? comp.suggest().get("exact") : null;
            if (exact != null) {
                exact.forEach(e -> {
                    if (e.completion() != null && e.completion().options() != null) {
                        e.completion().options().forEach(o -> out.add(o.text()));
                    }
                });
            }
            var fuzzy = comp.suggest() != null ? comp.suggest().get("fuzzy") : null;
            if (fuzzy != null) {
                fuzzy.forEach(f -> {
                    if (f.completion() != null && f.completion().options() != null) {
                        f.completion().options().forEach(o -> out.add(o.text()));
                    }
                });
            }

            // === 2) ASyT fallback (boardId 있으면 term 필터 추가) ===
            if (out.size() < LIMIT) {
                var asyt = es.search(s -> s
                        .index(INDEX_POSTS_SUGGEST)
                        .size(PER_PIPE)
                        .timeout("300ms")
                        .source(src -> src.filter(f -> f.includes("content")))
                        .query(q -> q.bool(b -> {
                            // board 필터
                            if (effectiveBoardId != null) {
                                b.filter(f -> f.term(t -> t.field("boardId").value(effectiveBoardId)));
                            }
                            // 차단 필터 ... (기존 그대로)
                            b.must(m -> m.multiMatch(mm -> mm
                                    .query(pfx).type(TextQueryType.BoolPrefix)
                                    .fields("content.asyt", "content.asyt._2gram", "content.asyt._3gram")));
                            return b;
                        })), Map.class);

                asyt.hits().hits().forEach(h -> {
                    Object c = h.source() == null ? null : h.source().get("content");
                    if (c instanceof String s && !s.isBlank()) out.add(s);
                });
            }

            return out.stream().limit(LIMIT).toList();

        } catch (Exception e) {
            log.error("[SUG] failed: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.ELASTICSEARCH_SEARCH_SUGGEST_FAILED);
        }
    }
}

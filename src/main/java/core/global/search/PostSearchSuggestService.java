package core.global.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.UserRepository;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
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
    private final ElasticsearchClient es;
    private final BlockRepository blockRepository;
    private final UserRepository userRepository;

    private static final int LIMIT = 5;
    private static final int PER_PIPE = 10;

    /** 하이브리드 서제스트: completion(정확/오타) + ASYT(fallback with filters) */
    public List<String> suggest(String prefix) {
        final String pfx = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        final boolean useFuzzy = pfx.length() >= 3;

        try {
            // viewer & 차단 유저
            String email = SecurityContextHolder.getContext().getAuthentication().getName();
            Long viewerId = userRepository.findByEmail(email).map(User::getId).orElse(null);
            List<Long> blockedIds = blockRepository.getBlockUsersByUserEmail(email)
                    .stream().map(User::getId).toList();

            // 1) completion: exact + fuzzy
            var comp = es.search(s -> s
                            .index(SearchConstants.INDEX_POSTS_SUGGEST)
                            .size(0)
                            .trackTotalHits(t -> t.enabled(false))
                            .suggest(sug -> sug
                                    .suggesters("exact", s1 -> s1
                                            .prefix(pfx)
                                            .completion(c -> c
                                                    .field("contentSuggestExact")
                                                    .skipDuplicates(true)
                                                    .size(PER_PIPE)
                                            )
                                    )
                                    .suggesters("fuzzy", s2 -> s2
                                            .prefix(pfx)
                                            .completion(c -> {
                                                var b = c.field("contentSuggest")
                                                        .skipDuplicates(true)
                                                        .size(PER_PIPE);
                                                return useFuzzy ? b.fuzzy(f -> f.fuzziness("AUTO")) : b;
                                            })
                                    )
                            ),
                    Map.class
            );

            var out = new LinkedHashSet<String>();

            // exact → fuzzy 순 병합
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

            // 2) ASYT fallback (차단만 필터; boardId 없음)
            if (out.size() < LIMIT) {
                var asyt = es.search(s -> s
                        .index(SearchConstants.INDEX_POSTS_SUGGEST)
                        .size(PER_PIPE)
                        .source(src -> src.filter(f -> f.includes("content")))
                        .query(q -> q.bool(b -> {
                            // 차단 유저 필터
                            if (blockedIds != null && !blockedIds.isEmpty()) {
                                if (blockedIds.size() < BLOCK_TERMS_LOOKUP_THRESHOLD) {
                                    var vals = blockedIds.stream().map(FieldValue::of).toList();
                                    b.mustNot(mn -> mn.terms(t -> t.field("userId").terms(ts -> ts.value(vals))));
                                } else if (viewerId != null) {
                                    b.mustNot(mn -> mn.terms(t -> t.field("userId").terms(ts -> ts
                                            .lookup(l -> l.index(USER_FILTER_INDEX)
                                                    .id(String.valueOf(viewerId))
                                                    .path(USER_FILTER_BLOCKED_PATH)))));
                                }
                            }
                            // 접두어 매칭
                            b.must(m -> m.multiMatch(mm -> mm
                                    .query(pfx)
                                    .type(TextQueryType.BoolPrefix)
                                    .fields("content.asyt","content.asyt._2gram","content.asyt._3gram")
                            ));
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

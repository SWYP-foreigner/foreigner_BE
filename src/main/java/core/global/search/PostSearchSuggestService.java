package core.global.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PostSearchSuggestService {
    private final ElasticsearchClient es;

    public List<String> suggest(String prefix, int size) {
        final int limit = Math.max(1, Math.min(size, 20));

        try {
            var resp = es.search(s -> s
                            .index(SearchConstants.INDEX_POSTS_SUGGEST)
                            .size(0)
                            .trackTotalHits(t -> t.enabled(false))
                            .suggest(sug -> sug
                                    .suggesters("exact", s1 -> s1
                                            .prefix(prefix)
                                            .completion(c -> c
                                                    .field("contentSuggestExact")
                                                    .skipDuplicates(true)
                                                    .size(limit)
                                            )
                                    )
                                    .suggesters("fuzzy", s2 -> s2
                                            .prefix(prefix)
                                            .completion(c -> c
                                                    .field("contentSuggest")
                                                    .skipDuplicates(true)
                                                    .size(limit)
                                                    .fuzzy(f -> f.fuzziness("AUTO"))
                                            )
                                    )
                            ),
                    PostDocument.class
            );

            var sug = resp.suggest().get("content-suggest");
            if (sug == null) return List.of();

            var out = new LinkedHashSet<String>();
            var exact = resp.suggest().get("exact");
            if (exact != null) {
                exact.forEach(e -> e.completion().options().forEach(o -> out.add(o.text())));
            }
            var fuzzy = resp.suggest().get("fuzzy");
            if (fuzzy != null) {
                fuzzy.forEach(f -> f.completion().options().forEach(o -> out.add(o.text())));
            }
            return out.stream().limit(size).toList();

        } catch (Exception e) {
            throw new BusinessException(ErrorCode.ELASTICSEARCH_SEARCH_SUGGEST_FAILED);
        }
    }
}

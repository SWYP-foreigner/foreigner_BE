package core.global.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
                                    .suggesters("content-suggest", s1 -> s1
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

            return sug.stream()
                    .flatMap(opt -> opt.completion().options().stream())
                    .map(o -> o.text())
                    .distinct()
                    .limit(size)
                    .toList();

        } catch (Exception e) {
            throw new BusinessException(ErrorCode.ELASTICSEARCH_SEARCH_SUGGEST_FAILED);
        }
    }
}

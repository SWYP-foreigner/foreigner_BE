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
        try {
            var resp = es.search(s -> s
                            .index(SearchConstants.INDEX_POSTS)
                            .suggest(sug -> sug
                                    .suggesters("content-suggest", s1 -> s1
                                            .prefix(prefix)
                                            .completion(c -> c
                                                    .field("contentSuggest")
                                                    .skipDuplicates(true)
                                                    .size(size)
                                            )
                                    )
                            )
                            .source(src -> src.fetch(false)),
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
            throw new BusinessException(ErrorCode.ELASTICSEARCH_SEARCH_FAILED);
        }
    }
}

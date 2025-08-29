package core.global.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

import static co.elastic.clients.elasticsearch._types.SortOrder.*;

@Service
@RequiredArgsConstructor
public class PostSearchService {

    private final ElasticsearchClient es;

    public record SearchResult(
            PostDocument doc,
            double score,
            String highlight // content 하이라이트 1개 조각
    ) {}

    public List<SearchResult> search(String query, Long boardId, int from, int size) {
        try {
            SearchResponse<PostDocument> resp = es.search(s -> s
                            .index(SearchConstants.INDEX_POSTS)
                            .from(from)
                            .size(size)
                            .query(q -> q.bool(b -> {
                                b.must(m -> m
                                        .match(mm -> mm
                                                .field("content")
                                                .query(query)
                                        )
                                );
                                if (boardId != null) {
                                    b.filter(f -> f.term(t -> t.field("boardId").value(boardId)));
                                }
                                return b;
                            }))
                            .sort(ss -> ss
                                    .score(o -> o.order(Desc))
                            )
                            .sort(ss -> ss
                                    .field(f -> f.field("createdAt")
                                            .order(Desc)))
                            .highlight(h -> h
                                    .fields("content", hf -> hf
                                            .numberOfFragments(1)
                                            .fragmentSize(140)))
                    ,
                    PostDocument.class
            );

            return resp.hits().hits().stream().map(hit -> {
                String hl = null;
                if (hit.highlight() != null && hit.highlight().get("content") != null
                    && !hit.highlight().get("content").isEmpty()) {
                    hl = hit.highlight().get("content").get(0);
                }
                return new SearchResult(hit.source(), hit.score() == null ? 0.0 : hit.score(), hl);
            }).toList();

        } catch (Exception e) {
            throw new BusinessException(ErrorCode.ELASTICSEARCH_SEARCH_FAILED);
        }
    }
}
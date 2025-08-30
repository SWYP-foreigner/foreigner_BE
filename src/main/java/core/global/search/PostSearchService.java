package core.global.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
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

    public List<SearchResult> search(String query, Long boardId, int from, int size) {
        try {
            SearchResponse<PostDocument> resp = es.search(s -> s
                            .index(SearchConstants.INDEX_POSTS)
                            .from(from)
                            .size(size)
                            .query(q -> q.bool(b -> {
                                // boardId 필터
                                if (boardId != null) {
                                    b.filter(f -> f.term(t -> t.field("boardId").value(boardId)));
                                }
                                // === 품질 업그레이드 핵심 ===
                                // 1) 정확 구문 우선 (가중치 ↑)
                                b.should(sh -> sh.matchPhrase(mp -> mp
                                        .field("content")
                                        .query(query)
                                        .slop(1)          // 단어 간 1칸 이동 허용
                                        .boost(3.0f)      // 점수 가중치 ↑
                                ));
                                // 2) 모든 단어가 포함된 일반 매치 (AND)
                                b.should(sh -> sh.match(m -> m
                                        .field("content")
                                        .query(query)
                                        .operator(Operator.And)
                                ));
                                // 3) 오타 허용 매치 (fuzziness)
                                b.should(sh -> sh.match(m -> m
                                        .field("content")
                                        .query(query)
                                        .fuzziness("AUTO")
                                ));
                                // should 중 하나만 맞아도 됨
                                b.minimumShouldMatch("1");
                                return b;
                            }))
                            // 정렬: 1순위 점수, 2순위 최신
                            .sort(ss -> ss.score(o -> o.order(SortOrder.Desc)))
                            .sort(ss -> ss.field(f -> f.field("createdAt").order(SortOrder.Desc)))
                            // 하이라이트
                            .highlight(h -> h
                                    .preTags("<em>").postTags("</em>")
                                    .fields("content", hf -> hf
                                            .numberOfFragments(1)
                                            .fragmentSize(140)))
                    ,
                    PostDocument.class
            );

            return resp.hits().hits().stream().map(hit -> {
                String hl = null;
                if (hit.highlight() != null &&
                    hit.highlight().get("content") != null &&
                    !hit.highlight().get("content").isEmpty()) {
                    hl = hit.highlight().get("content").get(0);
                }
                return new SearchResult(
                        hit.source(),
                        hit.score() == null ? 0.0 : hit.score(),
                        hl
                );
            }).toList();

        } catch (Exception e) {
            throw new BusinessException(ErrorCode.ELASTICSEARCH_SEARCH_FAILED);
        }
    }
}
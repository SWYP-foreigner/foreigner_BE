package core.domain.post.controller;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import core.domain.post.entity.Post;
import core.domain.post.repository.PostRepository;
import core.global.search.PostDocument;
import core.global.search.PostSearchService;
import core.global.search.SearchConstants;
import core.global.search.SearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class PostSearchController {

    private final PostSearchService searchService;

    @GetMapping("/posts")
    public List<SearchResult> searchPosts(
            @RequestParam String q,
            @RequestParam(required = false) Long boardId,
            @RequestParam(defaultValue = "0") int from,
            @RequestParam(defaultValue = "10") int size
    ) {
        return searchService.search(q, boardId, from, size);
    }

    private final ElasticsearchClient es;
    private final PostRepository postRepository;

    @PostMapping("/reindex-bulk")
    @Transactional(readOnly = true)
    public Map<String,Object> reindexBulk(
            @RequestParam(defaultValue = "500") int batchSize) throws Exception {

        long total = 0L;
        int page = 0;

        while (true) {
            var pageable = org.springframework.data.domain.PageRequest.of(page, batchSize);
            var pageData = postRepository.findAll(pageable);
            if (pageData.isEmpty()) break;

            var br = new co.elastic.clients.elasticsearch.core.BulkRequest.Builder();

            for (Post p : pageData.getContent()) {
                var doc = new PostDocument(
                        p.getId(),
                        p.getBoard().getId(),
                        p.getAuthor().getId(),
                        p.getAnonymous(),
                        p.getCreatedAt(),
                        p.getUpdatedAt(),
                        p.getCheckCount(),
                        p.getContent()
                );
                br.operations(op -> op.index(idx -> idx
                        .index(SearchConstants.INDEX_POSTS)             // 별칭에 쏘기
                        .id(String.valueOf(doc.postId()))
                        .document(doc)
                ));
            }

            var bulk = es.bulk(br.build());
            if (bulk.errors()) {
                // 실패 항목 로깅
                bulk.items().stream()
                        .filter(i -> i.error() != null)
                        .forEach(i -> System.err.println("Bulk error: " + i.error().reason()));
                throw new RuntimeException("Bulk indexing had errors");
            }

            total += pageData.getNumberOfElements();
            page++;
        }

        es.indices().refresh(r -> r.index(SearchConstants.INDEX_POSTS)); // 개발용
        return Map.of("indexed", total);
    }
}
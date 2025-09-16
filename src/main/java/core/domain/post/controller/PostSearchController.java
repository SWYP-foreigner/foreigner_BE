package core.domain.post.controller;

import core.global.search.dto.SearchResultView;
import core.global.search.service.PostSearchService;
import core.global.search.service.PostSearchSuggestService;
import core.global.search.service.RecentSearchRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class PostSearchController {

    private final PostSearchService searchService;
    private final PostSearchSuggestService suggestService;
    private final RecentSearchRedisService recentService;

    @GetMapping("/{boardId}/posts")
    public List<SearchResultView> searchPosts(
            @RequestParam String q,
            @PathVariable(required = false) Long boardId
    ) {
        return searchService.search(q, boardId);
    }

    @GetMapping("/{boardId}/suggest")
    public List<String> suggestByBoard(@PathVariable Long boardId,
                                       @RequestParam("q") String q) {
        return suggestService.suggest(q, boardId);
    }

    @GetMapping("/recent")
    public List<String> recent() {
        return recentService.list();
    }

    @DeleteMapping("/recent")
    public void deleteRecent(@RequestParam String q) {
        recentService.remove(q);
    }

    @DeleteMapping("/recent/all")
    public void clearRecent() {
        recentService.clear();
    }
}
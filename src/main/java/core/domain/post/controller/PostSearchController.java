package core.domain.post.controller;

import core.global.search.PostSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class PostSearchController {

    private final PostSearchService searchService;

    @GetMapping("/posts")
    public List<PostSearchService.SearchResult> searchPosts(
            @RequestParam String q,
            @RequestParam(required = false) Long boardId,
            @RequestParam(defaultValue = "0") int from,
            @RequestParam(defaultValue = "10") int size
    ) {
        return searchService.search(q, boardId, from, size);
    }
}
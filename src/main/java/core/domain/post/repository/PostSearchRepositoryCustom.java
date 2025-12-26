package core.domain.post.repository;

import core.domain.post.dto.search.SearchResultView;
import core.domain.post.dto.search.PostSearchRequest;

import java.util.List;

public interface PostSearchRepositoryCustom {
    List<String> suggest(String q, Long resolvedBoardId, List<Long> blockedIds, int limit);

    List<SearchResultView> search(PostSearchRequest request);

    List<String> findHotKeywordsOrTitles(int topN);
}


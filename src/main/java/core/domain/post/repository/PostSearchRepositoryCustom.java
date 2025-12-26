package core.domain.post.repository;

import core.domain.post.dto.search.SearchResultView;

import java.time.Instant;
import java.util.List;

public interface PostSearchRepositoryCustom {
    List<String> suggest(String q, Long resolvedBoardId, List<Long> blockedIds, int limit);

    List<SearchResultView> search(String q, Long userId, Long boardId, List<Long> blockedIds,
                                  Double afterScore, Instant afterTime, Long afterId, int limit);

    List<String> findHotKeywordsOrTitles(int topN);
}


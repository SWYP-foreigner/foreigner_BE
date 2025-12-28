package core.domain.post.repository;

import core.domain.post.dto.search.PostSearchProjection;
import core.domain.post.dto.search.PostSearchRequest;

import java.util.List;

public interface PostSearchRepositoryCustom {
    List<String> suggest(String q, Long resolvedBoardId, List<Long> blockedIds, int limit);

    List<PostSearchProjection> search(PostSearchRequest request);

    List<Object[]> findHotKeywordsOrTitles(int topN);
}


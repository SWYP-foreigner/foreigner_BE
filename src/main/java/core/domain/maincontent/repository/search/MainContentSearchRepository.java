package core.domain.maincontent.repository.search;

import core.domain.maincontent.dto.MainContentSearchProjection;
import core.domain.maincontent.dto.MainPageSearchRequest;

import java.util.List;

public interface MainContentSearchRepository {
    List<MainContentSearchProjection> search(MainPageSearchRequest request);

    @SuppressWarnings("unchecked")
    List<Object[]> findHotKeywordsOrTitles(int topN);

    List<String> suggest(String q, int limit);

    @SuppressWarnings("unchecked")
    List<Object[]> findEntitiesForChips(int topN);
}

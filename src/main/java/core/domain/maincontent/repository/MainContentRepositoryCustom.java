package core.domain.maincontent.repository;

import core.domain.maincontent.dto.MainContentNewsListResponse;
import core.domain.maincontent.entity.KNewsContentType;

import java.time.Instant;
import java.util.List;

public interface MainContentRepositoryCustom  {
    List<MainContentNewsListResponse> findLatestNews(KNewsContentType type, Instant cursorCreatedAt, Long cursorId, int size);

    List<MainContentNewsListResponse> findPopularNews(KNewsContentType type, Instant since, Long cursorScore, Long cursorId, int size);
}

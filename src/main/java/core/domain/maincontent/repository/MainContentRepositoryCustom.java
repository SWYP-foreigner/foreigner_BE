package core.domain.maincontent.repository;

import core.domain.admin.dto.MainContentListResponse;
import core.domain.admin.dto.MainContentSearchRequest;
import core.domain.maincontent.dto.MainContentNewsListResponse;
import core.global.enums.KNewsContentType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

public interface MainContentRepositoryCustom  {
    List<MainContentNewsListResponse> findLatestNews(KNewsContentType type, Instant cursorCreatedAt, Long cursorId, int size);

    List<MainContentNewsListResponse> findPopularNews(KNewsContentType type, Instant since, Long cursorScore, Long cursorId, int size);

    Page<MainContentListResponse> searchByAdmin(MainContentSearchRequest request, Pageable pageable);
}

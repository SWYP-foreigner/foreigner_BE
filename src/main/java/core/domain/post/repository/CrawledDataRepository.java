package core.domain.post.repository;

import core.domain.post.entity.CrawledData;
import core.global.enums.CrawledDataStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawledDataRepository extends JpaRepository<CrawledData, Long> {

    boolean existsByOriginalUrl(String originalUrl);

    Page<CrawledData> findByStatus(CrawledDataStatus status, Pageable pageable);
}

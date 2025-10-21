package core.domain.post.repository;

import core.domain.post.entity.CrawledData;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawledDataRepository extends JpaRepository<CrawledData, Long> {

    boolean existsByOriginalUrl(String originalUrl);
}

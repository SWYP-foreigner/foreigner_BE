package core.domain.maincontent.repository;

import core.domain.maincontent.entity.KNewsContentType;
import core.domain.maincontent.entity.MainPageContent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MainContentRepository extends JpaRepository<MainPageContent, Long> {
    List<MainPageContent> findTop3ByTypeOrderByViewCountDesc(KNewsContentType type);

    List<MainPageContent> findTop9ByTypeOrderByViewCountDesc();
}

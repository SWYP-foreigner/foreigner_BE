package core.domain.maincontent.repository;

import core.domain.maincontent.entity.KNewsContentType;
import core.domain.maincontent.entity.MainPageContent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MainContentRepository extends JpaRepository<MainPageContent, Long>, MainContentRepositoryCustom {
    List<MainPageContent> findTop3ByTypeOrderByViewCountDesc(KNewsContentType type);

    List<MainPageContent> findTop9ByOrderByViewCountDesc();
}

package core.domain.maincontent.repository;

import core.domain.maincontent.entity.MainContent;
import core.global.enums.KNewsContentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MainContentRepository extends JpaRepository<MainContent, Long>, MainContentRepositoryCustom {
    List<MainContent> findTop3ByTypeOrderByViewCountDesc(KNewsContentType type);

    List<MainContent> findTop9ByOrderByViewCountDesc();
}

package core.domain.maincontent.repository;

import core.domain.maincontent.entity.MainPageContent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MainContentRepository extends JpaRepository<MainPageContent, Long> {
}

package core.domain.mainpage.repository;

import core.domain.mainpage.service.KNewsContentType;
import core.domain.post.entity.MainPageContent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KoreanNewsRepository extends JpaRepository<MainPageContent, Long> {
    List<MainPageContent> findTop3ByTypeOrderByCreatedAtDesc(KNewsContentType type);
}

package core.domain.post.repository;

import core.domain.post.entity.HotKeywords;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HotKeywordRepository extends JpaRepository<HotKeywords, String> {
    // 빈도순으로 상위 N개 가져오기
    List<HotKeywords> findTop2000ByOrderByFrequencyDesc();
}
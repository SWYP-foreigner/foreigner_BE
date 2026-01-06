package core.domain.maincontent.repository.search;

import core.domain.maincontent.entity.MainContentHotKeywords;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MainContentHotKeywordRepository extends JpaRepository<MainContentHotKeywords, String> {
    List<MainContentHotKeywords> findTop2000ByOrderByFrequencyDesc();

    // 무작위 추출 후보군으로 사용할 상위 100개 조회
    List<MainContentHotKeywords> findTop100ByOrderByFrequencyDesc();
}
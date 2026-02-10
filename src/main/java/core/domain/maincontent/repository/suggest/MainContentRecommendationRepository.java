package core.domain.maincontent.repository.suggest;

import core.domain.maincontent.entity.MainContentRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface MainContentRecommendationRepository extends JpaRepository<MainContentRecommendation, String> {
    // 칩 UI에 뿌릴 상위 100개 후보군 조회
    List<MainContentRecommendation> findTop100ByOrderByFrequencyDesc();
}
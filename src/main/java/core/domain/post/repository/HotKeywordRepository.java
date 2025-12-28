package core.domain.post.repository;

import core.domain.post.entity.HotKeyword;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HotKeywordRepository extends JpaRepository<HotKeyword, String> {
    // 빈도순으로 상위 N개 가져오기
    List<HotKeyword> findTop2000ByOrderByFrequencyDesc();
}
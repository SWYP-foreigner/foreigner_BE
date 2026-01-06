package core.domain.maincontent.service.search;

import core.domain.maincontent.entity.MainContentRecommendation;
import core.domain.maincontent.repository.search.MainContentSearchRepository;
import core.domain.maincontent.entity.MainContentHotKeywords;
import core.domain.maincontent.repository.search.MainContentHotKeywordRepository;
import core.domain.maincontent.repository.suggest.MainContentRecommendationRepository;
import core.domain.post.service.MainContentSuggestIndex;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MainContentHotKeywordBatchService {

    private final MainContentSearchRepository searchRepository;
    private final MainContentHotKeywordRepository hotKeywordRepository;
    private final MainContentSuggestIndex memoryIndex;
    private final MainContentRecommendationRepository recommendationRepository;

    @PostConstruct
    public void init() {
        log.info("[Batch-Init] 서버 시작 시 수동 배치 실행");
        updateMainHotKeywordsBatch();
    }

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void updateMainHotKeywordsBatch() {
        log.info("[Batch-Main] 데이터 이원화 업데이트 시작 (자동완성 누적 / 추천칩 갱신)");

        try {
            // 1. 자동완성 (Autocomplete): 검색 편의를 위해 지우지 않고 "덧붙여 나감"
            List<Object[]> autocompleteResults = searchRepository.findHotKeywordsOrTitles(2000);
            if (!autocompleteResults.isEmpty()) {
                List<MainContentHotKeywords> newHotKeywords = autocompleteResults.stream()
                        .map(row -> MainContentHotKeywords.builder()
                                .keyword((String) row[0])
                                .frequency(((Number) row[1]).intValue())
                                .updatedAt(Instant.now())
                                .build())
                        .toList();

                // [DB/Memory] 삭제 없이 추가만 수행 (Append)
                hotKeywordRepository.saveAll(newHotKeywords);
                Map<String, Integer> additionalData = newHotKeywords.stream()
                        .collect(Collectors.toMap(MainContentHotKeywords::getKeyword, mk -> 1, (v1, v2) -> v1));
                memoryIndex.putAll(additionalData);
            }

            // 2. 추천칩 (Recommendations): 검색 전 'BTS', 'RM' 등 최신 고유명사 노출 (기존 것 삭제 후 갱신)
            List<Object[]> entityResults = searchRepository.findEntitiesForChips(100);
            if (!entityResults.isEmpty()) {
                // 추천 전용 테이블만 깔끔하게 비우고 새로 삽입 (Refresh)
                recommendationRepository.deleteAllInBatch();

                List<MainContentRecommendation> newRecs = entityResults.stream()
                        .map(row -> MainContentRecommendation.builder()
                                .keyword((String) row[0])
                                .frequency(((Number) row[1]).intValue())
                                .updatedAt(Instant.now())
                                .build())
                        .toList();
                recommendationRepository.saveAll(newRecs);
            }

            log.info("[Batch-Main] 업데이트 완료: 자동완성(누적) / 추천칩(갱신)");

        } catch (Exception e) {
            log.error("[Batch-Main] 배치 작업 중 에러 발생", e);
        }
    }
}
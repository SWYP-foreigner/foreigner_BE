package core.domain.post.service;

import core.domain.post.entity.HotKeywords;
import core.domain.post.repository.HotKeywordRepository;
import core.domain.post.repository.PostSearchRepositoryCustom;
import core.domain.post.service.search.SuggestMemoryIndex;
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
public class HotKeywordBatchService {

    private final PostSearchRepositoryCustom searchRepository;
    private final HotKeywordRepository hotKeywordRepository;
    private final SuggestMemoryIndex memoryIndex;

    @Scheduled(cron = "0 0 * * * *") // 매 정각 실행
    @Transactional
    public void updateHotKeywordsBatch() {
        log.info("[Batch] Starting Hot Keyword update task...");

        try {
            int topN = 2000;
            // 1. 무거운 연산 수행 (Repository 호출)
            List<Object[]> results = searchRepository.findHotKeywordsOrTitles(topN);

            if (results.isEmpty()) return;

            // 2. DB 테이블 갱신 (기존 데이터 삭제 후 대량 삽입)
            hotKeywordRepository.deleteAllInBatch();

            List<HotKeywords> newKeywords = results.stream()
                    .map(row -> HotKeywords.builder()
                            .keyword((String) row[0])
                            .frequency(((Number) row[1]).intValue())
                            .updatedAt(Instant.now())
                            .build())
                    .toList();

            hotKeywordRepository.saveAll(newKeywords);

            Map<String, Integer> nextData = newKeywords.stream()
                    .collect(Collectors.toMap(
                            HotKeywords::getKeyword,
                            hk -> 1, // 혹은 hk.getFrequency()
                            (v1, v2) -> v1
                    ));

            memoryIndex.replaceAll(nextData);

            log.info("[Batch] Successfully updated {} hot keywords to DB and Memory.", newKeywords.size());

        } catch (Exception e) {
            log.error("[Batch] Error occurred during hot keyword update", e);
        }
    }
}
package core.domain.post.service;

import core.domain.post.entity.HotKeywords;
import core.domain.post.repository.HotKeywordRepository;
import core.domain.post.repository.PostSearchRepositoryCustom;
import core.domain.post.service.search.PostSuggestIndex;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HotKeywordBatchService {

    private final PostSearchRepositoryCustom searchRepository;
    private final HotKeywordRepository hotKeywordRepository;
    private final PostSuggestIndex memoryIndex;

    @PostConstruct
    public void init() {
    log.info("start");
        updateHotKeywordsBatch();
        log.info("end");
    }

    @Scheduled(cron = "0 0 * * * *") // 매 정각 실행
    @Transactional
    public void updateHotKeywordsBatch() {
        log.info("[Batch] Starting Hot Keyword update task...");

        try {
            int topN = 10000;
            // 1. 무거운 연산 수행 (Repository 호출)
            List<Object[]> results = searchRepository.findHotKeywordsOrTitles(topN);

            if (results.isEmpty()) {
                log.warn("[Batch] DB 결과가 여전히 0건입니다. 게시글 데이터 자체를 확인해보세요.");
                return;
            }

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

            Map<String, Integer> nextData = new HashMap<>();

            for (Object[] row : results) {
                String rawTerm = (String) row[0];
                int freq = ((Number) row[1]).intValue();

                String cleaned = refineGoogleStyle(rawTerm);
                String[] words = cleaned.split(" ");

                StringBuilder phrase = new StringBuilder();
                for (int i = 0; i < Math.min(words.length, 3); i++) {
                    if (i > 0) phrase.append(" ");
                    phrase.append(words[i]);

                    String p = phrase.toString().trim();
                    if (!p.isEmpty()) {
                        nextData.merge(p, freq, Integer::sum);
                    }
                }
            }

            memoryIndex.replaceAll(nextData);

            log.info("[Batch] Successfully updated {} hot keywords to DB and Memory.", newKeywords.size());

        } catch (Exception e) {
            log.error("[Batch] Error occurred during hot keyword update", e);
        }
    }

    private String refineGoogleStyle(String raw) {
        if (raw == null || raw.isBlank()) return "";

        // 1. 소유격 's 제거 (대소문자 구분 없이 제거)
        String cleaned = raw.replaceAll("(?i)\\s*'?s\\b", "");

        // 2. 특수문자 제거 (알파벳, 숫자, 한글, 공백만 남김)
        cleaned = cleaned.replaceAll("[^a-zA-Z0-9가-힣\\s]", " ").trim();

        // 3. 연속된 공백 하나로 축소
        cleaned = cleaned.replaceAll("\\s+", " ");

        String[] words = cleaned.split(" ");
        if (words.length > 3) { // 추가 단어 1개를 포함해 총 3단어까지 유지
            return String.join(" ", words[0], words[1], words[2]);
        }

        return cleaned;
    }
}
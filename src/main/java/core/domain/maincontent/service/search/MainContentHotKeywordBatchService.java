package core.domain.maincontent.service.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.domain.maincontent.entity.MainContentHotKeywords;
import core.domain.maincontent.entity.MainContentRecommendation;
import core.domain.maincontent.repository.search.MainContentHotKeywordRepository;
import core.domain.maincontent.repository.search.MainContentSearchRepository;
import core.domain.maincontent.repository.suggest.MainContentRecommendationRepository;
import core.domain.post.service.MainContentSuggestIndex;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
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
public class MainContentHotKeywordBatchService {

    private final MainContentSearchRepository searchRepository;
    private final MainContentHotKeywordRepository hotKeywordRepository;
    private final MainContentSuggestIndex memoryIndex;
    private final MainContentRecommendationRepository recommendationRepository;

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    @PostConstruct
    public void init() {
        log.info("[Batch-Init] 서버 시작 시 수동 배치 실행");
        log.info("[Warmup] 서버 시작 시 초기화 작업 시작");

        // 1. 추천 키워드(JSON)를 DB에 딱 한 번 업로드
        loadManualRecommendations();

        // 2. 초기 자동완성 데이터 메모리 로드
        updateMainHotKeywordsBatch();

        log.info("[Warmup] 초기화 작업 완료");
    }

    /**
     * [Warmup 전용] JSON 파일을 읽어 추천 칩 테이블(Refresh) 갱신
     */
    @Transactional
    protected void loadManualRecommendations() {
        log.info("[Warmup-Rec] JSON 키워드 검증 및 업로드 시작...");
        try {
            Resource resource = resourceLoader.getResource("classpath:k_news_keywords.json");
            List<String> manualKeywords = objectMapper.readValue(
                    resource.getInputStream(),
                    new TypeReference<List<String>>() {}
            );

            if (!manualKeywords.isEmpty()) {
                int count = 0;
                for (String keyword : manualKeywords) {
                    // 1. 실제로 해당 키워드를 가진 콘텐츠가 있는지 DB에서 확인
                    if (searchRepository.existsInContents(keyword)) {

                        // 2. 존재한다면 Upsert (기존 점수 보존)
                        recommendationRepository.findById(keyword).ifPresentOrElse(
                                existing -> log.debug("[Warmup-Rec] 기존 키워드 유지: {}", keyword),
                                () -> {
                                    MainContentRecommendation newRec = MainContentRecommendation.builder()
                                            .keyword(keyword)
                                            .frequency(0) // 초기 점수
                                            .updatedAt(Instant.now())
                                            .build();
                                    recommendationRepository.save(newRec);
                                }
                        );
                        count++;
                    } else {
                        log.warn("[Warmup-Rec] 콘텐츠가 없어 제외된 키워드: {}", keyword);
                    }
                }
                log.info("[Warmup-Rec] 검증 완료: 총 {}개 중 {}개 키워드 등록됨", manualKeywords.size(), count);
            }
        } catch (Exception e) {
            log.error("[Warmup-Rec] JSON 검증 중 에러 발생", e);
        }
    }

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void updateMainHotKeywordsBatch() {
        log.info("[Batch-Main] 데이터 업데이트 시작 (자동완성 누적 갱신)");

        try {
            List<Object[]> autocompleteResults = searchRepository.findHotKeywordsOrTitles(2000);
            if (!autocompleteResults.isEmpty()) {
                List<MainContentHotKeywords> newHotKeywords = autocompleteResults.stream()
                        .map(row -> MainContentHotKeywords.builder()
                                .keyword((String) row[0])
                                .frequency(((Number) row[1]).intValue())
                                .updatedAt(Instant.now())
                                .build())
                        .toList();

                hotKeywordRepository.saveAll(newHotKeywords);

                Map<String, Integer> nextData = new HashMap<>();
                for (Object[] row : autocompleteResults) {
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
                memoryIndex.putAll(nextData);
            }
        } catch (Exception e) {
            log.error("[Batch-Main] 배치 작업 중 에러 발생", e);
        }
    }

    private String refineGoogleStyle(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String cleaned = raw.replaceAll("(?i)\\s*'?s\\b", "");
        cleaned = cleaned.replaceAll("[^a-zA-Z0-9가-힣\\s]", " ").trim();
        cleaned = cleaned.replaceAll("\\s+", " ");

        String[] words = cleaned.split(" ");
        if (words.length > 3) { // 3단어까지만 유지
            return String.join(" ", words[0], words[1], words[2]);
        }
        return cleaned;
    }

    @Transactional
    public void updateRecommendationsWithManualKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return;

        try {
            for (String keyword : keywords) {
                String trimmedKeyword = keyword.trim();
                if (trimmedKeyword.isEmpty()) continue;

                recommendationRepository.findById(trimmedKeyword).ifPresentOrElse(
                        existing -> {
                            // 1. 이미 존재하는 키워드면 점수(frequency) 가중치 부여
                            MainContentRecommendation updated = MainContentRecommendation.builder()
                                    .keyword(existing.getKeyword())
                                    .frequency(existing.getFrequency() + 10) // 수동 주입은 중요도가 높으므로 +10
                                    .updatedAt(Instant.now())
                                    .build();
                            recommendationRepository.save(updated);
                        },
                        () -> {
                            // 2. 새로운 키워드면 새로 등록
                            MainContentRecommendation newRec = MainContentRecommendation.builder()
                                    .keyword(trimmedKeyword)
                                    .frequency(20) // 처음 등록 시 높은 기본 점수 부여
                                    .updatedAt(Instant.now())
                                    .build();
                            recommendationRepository.save(newRec);
                            log.info("[Rec-Manual] 신규 추천 키워드 직접 주입: {}", trimmedKeyword);
                        }
                );
            }
        } catch (Exception e) {
            log.error("[Rec-Manual] 키워드 주입 중 에러 발생", e);
        }
    }
}
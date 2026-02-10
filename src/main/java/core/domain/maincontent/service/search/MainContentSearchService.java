package core.domain.maincontent.service.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import core.domain.maincontent.dto.MainContentNewsListResponse;
import core.domain.maincontent.dto.MainContentSearchProjection;
import core.domain.maincontent.dto.MainContentsSearchResultView;
import core.domain.maincontent.dto.MainPageSearchRequest;
import core.domain.maincontent.entity.MainContentHotKeywords;
import core.domain.maincontent.entity.MainContentRecommendation;
import core.domain.maincontent.repository.search.MainContentHotKeywordRepository;
import core.domain.maincontent.repository.search.MainContentSearchRepository;
import core.domain.maincontent.repository.suggest.MainContentRecommendationRepository;
import core.domain.post.service.MainContentSuggestIndex;
import core.global.entity.image.repository.ImageRepository;
import core.global.pagination.CursorPageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MainContentSearchService {

    private static final int LIMIT = 9;
    private static final int FAST_FIRST_MAX = 9;   // 메모리 최대
    private static final int DB_FALLBACK_MAX = 3;  // DB 최대
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    private final MainContentSearchRepository mainContentSearchRepository;
    private final MainContentRecommendationRepository recommendationRepository;
    private final ImageRepository imageRepository;
    private final MainContentSuggestIndex memoryIndex;

    @Transactional(readOnly = true)
    public CursorPageResponse<MainContentsSearchResultView> search(String q, String cursor, int size) {
        final int pageSize = Math.min(Math.max(size, 1), 20);

        Map<String, Object> c = safeDecode(cursor);
        Instant afterTime = parseInstant(c.get("t"));
        Long afterId = (c.get("id") instanceof Number n) ? n.longValue() : null;
        Double afterScore = (c.get("sc") instanceof Number n) ? n.doubleValue() : null; // score 추가

        // 3. 레포지토리 호출 (Projection 리스트 조회)
        List<MainContentSearchProjection> allProjections = mainContentSearchRepository.search(
                new MainPageSearchRequest(q, afterScore, afterTime, afterId, pageSize));

        // 4. 페이징 처리 (hasNext 여부 확인 후 데이터 절단)
        boolean hasNext = allProjections.size() > pageSize;
        List<MainContentSearchProjection> contentProjections = hasNext ? allProjections.subList(0, pageSize) : allProjections;

        // 5. 벌크 조회를 위한 ID 추출
        List<Long> contentsIds = contentProjections.stream().map(MainContentSearchProjection::contentId).toList();

        // 6. 벌크 데이터 조회 (Map/Set 변환)
        Map<Long, String> contentThumbnails = convertToStringMap(imageRepository.findFirstUrlsByMainContentsIds(contentsIds));

        // 7. 최종 DTO 조립
        List<MainContentsSearchResultView> items = contentProjections.stream().map(content -> {
            MainContentNewsListResponse item = new MainContentNewsListResponse(
                    content.contentId(),
                    content.title(),
                    content.type(),
                    calculateDaysAgo(content.createdAt()),
                    content.createdAt(),
                    contentThumbnails.get(content.contentId()),
                    content.scoreRounded()
            );
            return new MainContentsSearchResultView(item, content.rawScore());
        }).toList();

        // 8. 다음 커서 생성
        String nextCursor = null;
        if (hasNext && !items.isEmpty()) {
            var last = items.get(items.size() - 1);
            nextCursor = safeEncode(Map.of(
                    "sc", last.score(),
                    "t", last.item().createdAt(),
                    "id", last.item().contentId()
            ));
        }

        return new CursorPageResponse<>(items, hasNext, nextCursor);

    }

    public List<String> getRandomHotKeywords() {
        log.info("[Recommendations] Fetching entities from dedicated table...");

        List<MainContentRecommendation> candidates = recommendationRepository.findTop100ByOrderByFrequencyDesc();
        log.info(">>>> DB에서 조회된 실제 개수: " + candidates.size());

        if (candidates.isEmpty()) return List.of();

        List<MainContentRecommendation> mutableCandidates = new ArrayList<>(candidates);
        Collections.shuffle(mutableCandidates);

        return mutableCandidates.stream()
                .limit(6)
                .map(MainContentRecommendation::getKeyword)
                .collect(Collectors.toList());
    }

    @Transactional
    public void increaseRecommendationScore(String keyword) {
        recommendationRepository.findById(keyword).ifPresent(rec -> {
            // 기존 frequency에 +1 (혹은 가중치만큼 부여)
            // 엔티티에 @Setter가 없다면 새로운 객체를 생성하거나 내부 메서드 활용
            MainContentRecommendation updatedRec = MainContentRecommendation.builder()
                    .keyword(rec.getKeyword())
                    .frequency(rec.getFrequency() + 1) // 점수 증가
                    .updatedAt(Instant.now())
                    .build();
            recommendationRepository.save(updatedRec);
            log.info("[Rec-Score] 키워드 '{}' 점수 상승: {} -> {}",
                    keyword, rec.getFrequency(), updatedRec.getFrequency());
        });
    }

    private long calculateDaysAgo(Instant createdAt) {
        if (createdAt == null) {
            return 0; // 예외 처리
        }

        return ChronoUnit.DAYS.between(createdAt, Instant.now());
    }

    private Map<Long, String> convertToStringMap(List<Object[]> result) {
        return result.stream().collect(Collectors.toMap(r -> (Long) r[0], r -> (String) r[1], (v1, v2) -> v1));
    }

    private Instant parseInstant(Object obj) {
        if (obj instanceof String s) return Instant.parse(s);
        if (obj instanceof Number n) {
            long sec = n.longValue();
            int nano = (int) ((n.doubleValue() - sec) * 1_000_000_000);
            return Instant.ofEpochSecond(sec, nano);
        }
        return null;
    }

//    @Transactional(readOnly = true)
    public List<String> suggest(String prefix) {
        String pfx = prefix == null ? "" : prefix.trim();
        if (pfx.isEmpty()) return List.of();

        // 1) 메모리 자동완성 우선 (최대 FAST_FIRST_MAX, 단 총 LIMIT 고려)
        List<String> fast = memoryIndex.suggestPrefix(pfx, FAST_FIRST_MAX);

        // 2) 부족분만 PGroonga로 보충 (최대 DB_FALLBACK_MAX, 단 총 LIMIT 고려)
        int remain = LIMIT - fast.size();
        List<String> db = (remain > 0) ? mainContentSearchRepository.suggest(pfx, remain) : List.of();

        // 3) 대소문자 중복 제거 머지
        Map<String, String> deduplicatedMap = new LinkedHashMap<>();

        for (String s : fast) {
            deduplicatedMap.putIfAbsent(s.toLowerCase().trim(), s);
        }

        for (String s : db) {
            if (deduplicatedMap.size() >= LIMIT) break;
            String cleaned = s.replaceAll("(?i)\\s*[''’]?s\\b", "").trim();
            deduplicatedMap.putIfAbsent(cleaned.toLowerCase(), cleaned);
        }

        return new ArrayList<>(deduplicatedMap.values());
    }

    private Map<String, Object> safeDecode(String cursor) {
        if (cursor == null || cursor.isBlank()) return Map.of();
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            // 매번 new 하지 않고 static mapper 사용
            return mapper.readValue(decoded, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String safeEncode(Map<String, Object> m) {
        if (m == null || m.isEmpty()) return null;
        try {
            // 1. 이미 생성된 MAPPER 재사용
            byte[] jsonBytes = mapper.writeValueAsBytes(m);
            // 2. 바이트 배열로 바로 인코딩 (속도 향상)
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(jsonBytes);
        } catch (Exception e) {
            log.error("Cursor encoding error", e);
            return null;
        }
    }
}

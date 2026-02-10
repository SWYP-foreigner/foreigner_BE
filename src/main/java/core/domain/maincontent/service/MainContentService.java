package core.domain.maincontent.service;

import core.domain.maincontent.controller.KNewsCategoryListResponse;
import core.domain.maincontent.dto.MainContentNewsListResponse;
import core.domain.maincontent.dto.MainContentNewsResponse;
import core.domain.maincontent.dto.MainContentTop9Response;
import core.domain.maincontent.dto.MainPageContentResponse;
import core.domain.maincontent.entity.MainContent;
import core.domain.maincontent.repository.MainContentRepository;
import core.global.entity.image.repository.ImageRepository;
import core.global.enums.KNewsContentType;
import core.global.enums.MainContentSortOption;
import core.global.enums.common.ImageType;
import core.global.enums.errorcode.MainContentErrorCode;
import core.global.exception.BusinessException;
import core.global.pagination.CursorCodec;
import core.global.pagination.CursorPageResponse;
import core.global.pagination.CursorPages;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MainContentService {

    private final MainContentRepository mainContentRepository;
    private final ImageRepository imageRepository;

    @Transactional(readOnly = true)
    public List<MainContentNewsResponse> getTop3News(KNewsContentType type) {

        List<MainContent> contents = mainContentRepository.findTop3ByTypeOrderByViewCountDesc(type);

        // 1. ID 리스트 추출
        List<Long> ids = contents.stream().map(MainContent::getId).toList();

        // 2. 이미지 벌크 조회 (Map으로 변환: Key=relatedId, Value=url)
        Map<Long, String> imageMap = getImageMap(ids);

        return contents.stream()
                .map(content -> new MainContentNewsResponse(
                        content.getId(),
                        content.getTitle(),
                        content.getType(),
                        calculateDaysAgo(content.getCreatedAt()),
                        imageMap.getOrDefault(content.getId(), null)
                ))
                .toList();
    }

    public List<MainContentTop9Response> getTrendingKNews() {
        List<MainContent> contents = mainContentRepository.findTop9ByOrderByViewCountDesc();

        List<Long> ids = contents.stream().map(MainContent::getId).toList();
        Map<Long, String> imageMap = getImageMap(ids);

        return contents.stream()
                .map(content -> new MainContentTop9Response(
                        content.getId(),
                        content.getTitle(),
                        content.getType(),
                        calculateDaysAgo(content.getCreatedAt()),
                        imageMap.getOrDefault(content.getId(), null)
                ))
                .toList();
    }


    private Map<Long, String> getImageMap(List<Long> ids) {
        if (ids.isEmpty()) return Collections.emptyMap();

        List<Object[]> results = imageRepository.findFirstUrlByRelatedIds(ImageType.MAIN_PAGE_THUMBNAIL, ids);

        return results.stream()
                .collect(Collectors.toMap(
                        result -> (Long) result[0],  // relatedId
                        result -> (String) result[1] // url
                ));
    }

    public MainPageContentResponse getMainContent(Long contentId) {
        MainContent content = mainContentRepository.findById(contentId)
                .orElseThrow(() -> new BusinessException(MainContentErrorCode.CONTENT_NOT_FOUND));

        content.addViewCount();
        return MainPageContentResponse.from(content);
    }

    private long calculateDaysAgo(Instant createdAt) {
        if (createdAt == null) {
            return 0; // 예외 처리
        }

        return ChronoUnit.DAYS.between(createdAt, Instant.now());
    }

    public CursorPageResponse<MainContentNewsListResponse> getCategoryNews(KNewsContentType type, MainContentSortOption sort, String cursor, int size) {
        final int pageSize = Math.min(Math.max(size, 1), 50);
        final Map<String, Object> c = safeDecode(cursor);

        return switch (sort) {
            case TRENDING -> handlePopular(type, c, pageSize);
            case NEW -> handleLatest(type, c, pageSize);
            default -> handleLatest(type, c, pageSize);
        };
    }

    // ------- 정렬 핸들러 -------
    private CursorPageResponse<MainContentNewsListResponse> handleLatest(KNewsContentType type, Map<String, Object> c, int pageSize) {
        var k = parseLatest(c); // t,id
        List<MainContentNewsListResponse> rows = mainContentRepository.findLatestNews(
                type,
                truncateToMillis(k.t),
                k.id,
                pageSize + 1
        );

        if (rows == null || rows.isEmpty()) {
            return new CursorPageResponse<>(List.of(), false, null);
        }

        return CursorPages.ofLatest(
                rows, pageSize,
                MainContentNewsListResponse::createdAt,
                MainContentNewsListResponse::contentId
        );
    }

    private CursorPageResponse<MainContentNewsListResponse> handlePopular(KNewsContentType type, Map<String, Object> c, int pageSize) {
        var k = parsePopular(c);
        Instant since = popularSince();
        List<MainContentNewsListResponse> rows = mainContentRepository.findPopularNews(
                type,
                since,
                k.sc,
                k.id,
                pageSize + 1
        );


        if (rows == null || rows.isEmpty()) {
            return new CursorPageResponse<>(List.of(), false, null);
        }

        return CursorPages.ofPopular(
                rows, pageSize,
                MainContentNewsListResponse::score,
                MainContentNewsListResponse::contentId
        );
    }


    // ------- 커서 파싱 -------
    private LatestKey parseLatest(Map<String, Object> c) {
        Instant t = null;
        Long id = null;
        Object ts = c.get("t");
        if (ts instanceof String s && !s.isBlank()) t = Instant.parse(s);
        Object idObj = c.get("id");
        if (idObj instanceof Number n) id = n.longValue();
        return new LatestKey(t, id);
    }

    private PopularKey parsePopular(Map<String, Object> c) {
        Long sc = null, id = null;
        Object scObj = c.get("sc");
        if (scObj instanceof Number n) sc = n.longValue();
        Object idObj = c.get("id");
        if (idObj instanceof Number n) id = n.longValue();
        return new PopularKey(sc, id);
    }

    public List<KNewsCategoryListResponse> getCategories() {
        return Arrays.stream(KNewsContentType.values())
                .map(KNewsCategoryListResponse::new)
                .toList();
    }

    private static final class LatestKey {
        final Instant t;
        final Long id;

        LatestKey(Instant t, Long id) {
            this.t = t;
            this.id = id;
        }
    }

    private static final class PopularKey {
        final Long sc;
        final Long id;

        PopularKey(Long sc, Long id) {
            this.sc = sc;
            this.id = id;
        }
    }

    private Instant truncateToMillis(Instant i) {
        return (i == null) ? null : i.truncatedTo(ChronoUnit.MILLIS);
    }

    private Instant popularSince() {
        return Instant.now().minus(Duration.ofDays(14));
    }


    private Map<String, Object> safeDecode(String cursor) {
        if (cursor == null || cursor.isBlank()) return Map.of();
        try {
            return CursorCodec.decode(cursor);
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }
}

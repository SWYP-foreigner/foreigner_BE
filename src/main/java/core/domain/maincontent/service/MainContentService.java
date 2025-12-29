package core.domain.maincontent.service;

import core.domain.maincontent.dto.MainContentTop9Response;
import core.domain.maincontent.dto.MainContentTop3Response;
import core.domain.maincontent.entity.KNewsContentType;

import core.domain.maincontent.dto.MainPageContentResponse;
import core.domain.maincontent.entity.MainPageContent;
import core.domain.maincontent.repository.MainContentRepository;
import core.global.entity.image.repository.ImageRepository;
import core.global.enums.ImageType;
import core.global.enums.errorcode.MainContentErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    public List<MainContentTop3Response> getTop3News(KNewsContentType type) {

        List<MainPageContent> contents = mainContentRepository.findTop3ByTypeOrderByViewCountDesc(type);

        // 1. ID 리스트 추출
        List<Long> ids = contents.stream().map(MainPageContent::getId).toList();

        // 2. 이미지 벌크 조회 (Map으로 변환: Key=relatedId, Value=url)
        Map<Long, String> imageMap = getImageMap(ids);

        return contents.stream()
                .map(content -> new MainContentTop3Response(
                        content.getId(),
                        content.getTitle(),
                        content.getHtmlContent(),
                        content.getType(),
                        calculateDaysAgo(content.getCreatedAt()),
                        imageMap.getOrDefault(content.getId(), null)
                ))
                .toList();
    }

    public List<MainContentTop9Response> getTrendingKNews() {
        List<MainPageContent> contents = mainContentRepository.findTop9ByOrderByViewCountDesc();

        List<Long> ids = contents.stream().map(MainPageContent::getId).toList();
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
        MainPageContent content = mainContentRepository.findById(contentId)
                .orElseThrow(() -> new BusinessException(MainContentErrorCode.CONTENT_NOT_FOUND));

        return MainPageContentResponse.from(content);
    }

    private long calculateDaysAgo(Instant createdAt) {
        if (createdAt == null) {
            return 0; // 예외 처리
        }

        return ChronoUnit.DAYS.between(createdAt, Instant.now());
    }
}

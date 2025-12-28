package core.domain.maincontent.service;

import core.domain.maincontent.dto.MainContentTop9Response;
import core.domain.maincontent.dto.MainContentTop3Response;
import core.domain.maincontent.entity.KNewsContentType;

import core.domain.maincontent.dto.MainPageContentResponse;
import core.domain.maincontent.entity.MainPageContent;
import core.domain.maincontent.repository.MainContentRepository;
import core.global.enums.errorcode.MainContentErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MainContentService {

    private final MainContentRepository mainContentRepository;

    @Transactional(readOnly = true)
    public List<MainContentTop3Response> getTop3News(KNewsContentType type) {
        return mainContentRepository.findTop3ByTypeOrderByViewCountDesc(type)
                .stream()
                .map(content -> new MainContentTop3Response(
                        content.getTitle(),
                        content.getHtmlContent(),
                        content.getType(),
                        Instant.now().compareTo(content.getCreatedAt())))
                .collect(Collectors.toList());
    }

    public List<MainContentTop9Response> getTrendingKNews() {
        return mainContentRepository.findTop9ByTypeOrderByViewCountDesc()
                .stream()
                .map(content -> new MainContentTop9Response(
                        content.getTitle(),
                        content.getType(),
                        Instant.now().compareTo(content.getCreatedAt())))
                .collect(Collectors.toList());
    }
    public MainPageContentResponse getMainContent(Long contentId) {
        MainPageContent content = mainContentRepository.findById(contentId)
                .orElseThrow(() -> new BusinessException(MainContentErrorCode.CONTENT_NOT_FOUND));

        return MainPageContentResponse.from(content);
    }
}

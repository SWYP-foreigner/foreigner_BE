package core.domain.maincontent.service;


import core.domain.maincontent.dto.MainPageContentResponse;
import core.domain.maincontent.entity.MainPageContent;
import core.domain.maincontent.repository.MainContentRepository;
import core.global.enums.errorcode.MainContentErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MainContentService {
    private final MainContentRepository mainPageContentRepository;

    public MainPageContentResponse getMainContent(Long contentId) {
        MainPageContent content = mainPageContentRepository.findById(contentId)
                .orElseThrow(() -> new BusinessException(MainContentErrorCode.CONTENT_NOT_FOUND));

        return MainPageContentResponse.from(content);
    }
}

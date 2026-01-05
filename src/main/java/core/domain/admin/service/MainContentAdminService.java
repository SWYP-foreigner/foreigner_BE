package core.domain.admin.service;

import core.domain.admin.dto.MainContentListResponse;
import core.domain.admin.dto.MainContentSearchRequest;
import core.domain.maincontent.entity.MainPageContent;
import core.domain.maincontent.repository.MainContentRepository;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageStorageClient;
import core.global.enums.ImageType;
import core.global.enums.errorcode.CommunityErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MainContentAdminService {

    private final MainContentRepository mainPageContentRepository;
    private final ImageRepository imageRepository;
    private final ImageStorageClient imageStorageClient;

    private static final List<ImageType> TARGET_IMAGE_TYPES = List.of(
            ImageType.MAIN_PAGE_THUMBNAIL,
            ImageType.MAIN_PAGE_POPULAR_THUMBNAIL,
            ImageType.MAIN_PAGE_VIDEO,
            ImageType.MAIN_PAGE_BODY
    );

    @Transactional(readOnly = true)
    public Page<MainContentListResponse> searchContents(MainContentSearchRequest request, Pageable pageable) {
        return mainPageContentRepository.searchByAdmin(request, pageable);
    }

    @Transactional
    public void deleteMainContent(Long id) {
        MainPageContent content = mainPageContentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.MAIN_PAGE_CONTENT_NOT_FOUND));

        deleteRelatedS3Images(id);
        deleteRelatedDbImages(id);
        mainPageContentRepository.delete(content);
    }

    private void deleteRelatedS3Images(Long contentId) {
        List<String> urlsToDelete = new ArrayList<>();

        for (ImageType type : TARGET_IMAGE_TYPES) {
            List<Object[]> rows = imageRepository.findAllUrlsByRelatedIds(type, List.of(contentId));
            for (Object[] row : rows) {
                String url = (String) row[1];
                urlsToDelete.add(url);
            }
        }

        if (!urlsToDelete.isEmpty()) {
            imageStorageClient.deleteObjectsByUrls(urlsToDelete);
            log.info("[MainContent] Related images delete requested: count={}", urlsToDelete.size());
        }
    }

    private void deleteRelatedDbImages(Long contentId) {
        for (ImageType type : TARGET_IMAGE_TYPES) {
            imageRepository.deleteByImageTypeAndRelatedId(type, contentId);
        }
    }
}

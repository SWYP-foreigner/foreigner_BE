package core.domain.admin.service;

import core.domain.admin.dto.MainContentDetailDto;
import core.domain.admin.dto.MainContentListResponse;
import core.domain.admin.dto.MainContentSearchRequest;
import core.domain.maincontent.entity.MainPageContent;
import core.domain.maincontent.repository.MainContentRepository;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageStorageClient;
import core.global.enums.ImageType;
import core.global.enums.KNewsContentType;
import core.global.enums.errorcode.CommunityErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

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

    @Transactional(readOnly = true)
    public MainContentDetailDto getDetail(Long id) {
        MainPageContent content = mainPageContentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.MAIN_PAGE_CONTENT_NOT_FOUND));

        List<Image> images = imageRepository.findByImageTypeAndRelatedIdIn(
                ImageType.MAIN_PAGE_BODY, List.of(id));

        String mainThumb = imageRepository.findTopByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.MAIN_PAGE_THUMBNAIL, id)
                .map(Image::getUrl).orElse(null);
        String popThumb = imageRepository.findTopByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.MAIN_PAGE_POPULAR_THUMBNAIL, id)
                .map(Image::getUrl).orElse(null);

        return MainContentDetailDto.from(content, mainThumb, popThumb, images);
    }

    @Transactional
    public void updateMainContent(Long id, String title, String kNewsTypeStr, String htmlContent,
                                  MultipartFile mainThumbFile, MultipartFile popThumbFile,
                                  List<MultipartFile> newContentImages) {

        MainPageContent content = mainPageContentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.MAIN_PAGE_CONTENT_NOT_FOUND));

        KNewsContentType type = KNewsContentType.valueOf(kNewsTypeStr);

        if (mainThumbFile != null && !mainThumbFile.isEmpty()) {
            replaceThumbnail(id, ImageType.MAIN_PAGE_THUMBNAIL, mainThumbFile);
        }
        if (popThumbFile != null && !popThumbFile.isEmpty()) {
            replaceThumbnail(id, ImageType.MAIN_PAGE_POPULAR_THUMBNAIL, popThumbFile);
        }

        String processedHtml = processHtmlAndSyncImages(id, htmlContent, newContentImages);
        content.update(title, processedHtml, type);
    }

    private void replaceThumbnail(Long contentId, ImageType type, MultipartFile file) {
        imageRepository.findTopByImageTypeAndRelatedIdOrderByOrderIndexAsc(type, contentId)
                .ifPresent(oldImg -> {
                    imageStorageClient.deleteObjectsByUrls(List.of(oldImg.getUrl()));
                    imageRepository.delete(oldImg);
                });
        String cdnUrl = uploadFileToStorage(file, contentId);

        imageRepository.save(Image.of(type, contentId, cdnUrl, 0));
    }

    private String processHtmlAndSyncImages(Long contentId, String rawHtml, List<MultipartFile> newFiles) {
        Document doc = Jsoup.parseBodyFragment(rawHtml);
        Elements imgTags = doc.select("img");

        Set<String> finalImageUrls = new HashSet<>();

        Map<String, String> urlCache = new HashMap<>();

        int orderIndex = 0;
        for (Element img : imgTags) {
            String src = img.attr("src");
            String dataFileIndex = img.attr("data-file-index");

            String finalUrl = src;

            if (StringUtils.hasText(dataFileIndex) && newFiles != null) {
                try {
                    int idx = Integer.parseInt(dataFileIndex);
                    if (idx >= 0 && idx < newFiles.size()) {
                        String cdnUrl = uploadFileToStorage(newFiles.get(idx), contentId);

                        img.attr("src", cdnUrl);
                        img.removeAttr("data-file-index");
                        finalUrl = cdnUrl;
                    }
                } catch (NumberFormatException ignored) {}
            }

            else if (src.startsWith("http") && !src.contains(imageStorageClient.generatePublicUrl(""))) {
                String cachedUrl = urlCache.get(src);

                if (cachedUrl != null) {
                    finalUrl = cachedUrl;
                } else {
                    String cdnUrl = uploadUrlToStorage(src, contentId);
                    if (cdnUrl != null) {
                        urlCache.put(src, cdnUrl);
                        finalUrl = cdnUrl;
                    }
                }
                img.attr("src", finalUrl);
            }

            finalImageUrls.add(finalUrl);

            if (!imageRepository.existsByRelatedIdAndUrlAndImageType(contentId, finalUrl, ImageType.MAIN_PAGE_BODY)) {
                imageRepository.save(Image.of(ImageType.MAIN_PAGE_BODY, contentId, finalUrl, orderIndex));
            }
            orderIndex++;
        }

        List<Image> currentDbImages = imageRepository.findByImageTypeAndRelatedId(ImageType.MAIN_PAGE_BODY, contentId);

        List<Image> imagesToDelete = currentDbImages.stream()
                .filter(img -> !finalImageUrls.contains(img.getUrl()))
                .collect(Collectors.toList());

        if (!imagesToDelete.isEmpty()) {
            List<String> urlsToDelete = imagesToDelete.stream().map(Image::getUrl).collect(Collectors.toList());
            imageStorageClient.deleteObjectsByUrls(urlsToDelete);
            imageRepository.deleteAll(imagesToDelete);
            log.info("Unused images deleted: {}", urlsToDelete.size());
        }

        return doc.body().html();
    }

    private String uploadUrlToStorage(String originalUrl, Long contentId) {
        try {
            String ext = getExtension(originalUrl);
            String key = "main-page/" + contentId + "/" + UUID.randomUUID() + ext;
            String uploadedKey = imageStorageClient.uploadFromUrl(originalUrl, key);

            if (uploadedKey != null) {
                return imageStorageClient.generatePublicUrl(key);
            }
        } catch (Exception e) {
            log.error("Failed to upload external image: {}", originalUrl, e);
        }
        return null;
    }

    private String uploadFileToStorage(MultipartFile file, Long contentId) {
        String ext = getExtension(file.getOriginalFilename());
        String key = "main-page/" + contentId + "/" + UUID.randomUUID() + ext;
        imageStorageClient.upload(file, key);
        return imageStorageClient.generatePublicUrl(key);
    }

    private String getExtension(String filename) {
        if (filename != null && filename.contains(".")) {
            String ext = filename.substring(filename.lastIndexOf(".")).toLowerCase();
            if (ext.contains("?")) {
                ext = ext.substring(0, ext.indexOf("?"));
            }
            if (List.of(".jpg", ".jpeg", ".png", ".gif", ".webp").contains(ext)) {
                return ext;
            }
        }
        return ".jpg";
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

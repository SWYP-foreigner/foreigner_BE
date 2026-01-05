package core.domain.admin.service;

import core.domain.board.entity.Board;
import core.domain.board.repository.BoardRepository;
import core.domain.post.dto.crawling.CrawledDataDto;
import core.domain.post.dto.crawling.MergedCrawledDataDto;
import core.domain.post.entity.CrawledData;
import core.domain.maincontent.entity.MainPageContent;
import core.domain.post.entity.Post;
import core.domain.post.repository.CrawledDataRepository;
import core.domain.maincontent.repository.MainContentRepository;
import core.domain.post.repository.PostRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.config.CustomUserDetails;
import core.global.entity.image.S3Props;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageStorageClient;
import core.global.entity.image.service.PostImageService;
import core.global.enums.CrawledDataStatus;
import core.global.enums.ImageType;
import core.global.enums.KNewsContentType;
import core.global.enums.errorcode.CommonErrorCode;
import core.global.enums.errorcode.CommunityErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrawledDataAdminService {

    private final CrawledDataRepository crawledDataRepository;
    private final PostRepository postRepository;
    private final MainContentRepository mainContentRepository;
    private final UserRepository userRepository;
    private final BoardRepository boardRepository;
    private final PostImageService postImageService;
    private final ImageRepository imageRepository;
    private final ImageStorageClient imageStorageClient;

    @Transactional(readOnly = true)
    public Page<CrawledDataDto> getPendingCrawledData(Pageable pageable) {
        return crawledDataRepository.findByStatus(CrawledDataStatus.PENDING, pageable)
                .map(CrawledDataDto::from);
    }

    @Transactional(readOnly = true)
    public MergedCrawledDataDto getMergedCrawledData(List<Long> ids) {
        List<CrawledData> dataList = crawledDataRepository.findAllById(ids);
        if (dataList.isEmpty()) {
            throw new BusinessException(CommunityErrorCode.CRAWLED_DATA_NOT_FOUND);
        }

        String mergedTitle = dataList.stream()
                .map(CrawledData::getTitle)
                .collect(Collectors.joining(" / "));

        String mergedContent = dataList.stream()
                .map(d -> "<h3>[" + d.getTitle() + "]</h3>\n" + (d.getContentSnippet() != null ? d.getContentSnippet() : ""))
                .collect(Collectors.joining("\n<br><hr><br>\n"));

        List<String> allImages = dataList.stream()
                .flatMap(d -> d.getImageUrls().stream())
                .distinct()
                .collect(Collectors.toList());

        return MergedCrawledDataDto.builder()
                .title(mergedTitle)
                .content(mergedContent)
                .imageUrls(allImages)
                .build();
    }

    @Transactional
    public void approveMergedData(List<Long> sourceIds, String title, String publishType, Long boardId, String kNewsTypeStr, String content,
                                  List<String> selectedImageUrls, String mainThumbnailUrl, String popularThumbnailUrl,
                                  MultipartFile mainThumbnailFile, MultipartFile popularThumbnailFile,
                                  List<MultipartFile> contentImages) {

        List<CrawledData> sourceDataList = crawledDataRepository.findAllById(sourceIds);
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        User adminUser = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        Long savedReferenceId = null;
        Map<String, String> uploadedUrlCache = new HashMap<>();

        if ("GENERAL".equals(publishType)) {
            if (boardId == null) throw new BusinessException(CommunityErrorCode.BOARD_NOT_FOUND);
            Board targetBoard = boardRepository.findById(boardId).orElseThrow(() -> new BusinessException(CommunityErrorCode.BOARD_NOT_FOUND));

            String finalContent = "# " + title + "\n\n" + content;
            Post newPost = new Post(finalContent, adminUser, targetBoard);
            Post savedPost = postRepository.save(newPost);
            savedReferenceId = savedPost.getId();

            if (selectedImageUrls != null && !selectedImageUrls.isEmpty()) {
                postImageService.uploadAndSavePostImagesFromUrls(savedPost, selectedImageUrls);
            }

        } else if ("MAIN_PAGE".equals(publishType)) {
            KNewsContentType kNewsType = null;
            if (StringUtils.hasText(kNewsTypeStr)) {
                try {
                    kNewsType = KNewsContentType.valueOf(kNewsTypeStr);
                } catch (IllegalArgumentException e) {
                    throw new BusinessException(CommonErrorCode.INVALID_INPUT);
                }
            }

            MainPageContent newContent = MainPageContent.builder()
                    .title(title)
                    .htmlContent(content)
                    .type(kNewsType)
                    .originalUrl(sourceDataList.get(0).getOriginalUrl())
                    .build();

            MainPageContent savedContent = mainContentRepository.save(newContent);
            Long contentId = savedContent.getId();
            savedReferenceId = contentId;

            String processedHtml = processHtmlAndUploadImages(content, contentId, uploadedUrlCache, contentImages);
            savedContent.changeHtmlContent(processedHtml);

            processAndSaveThumbnail(contentId, mainThumbnailFile, mainThumbnailUrl, ImageType.MAIN_PAGE_THUMBNAIL, uploadedUrlCache);
            processAndSaveThumbnail(contentId, popularThumbnailFile, popularThumbnailUrl, ImageType.MAIN_PAGE_POPULAR_THUMBNAIL, uploadedUrlCache);
        }

        for (CrawledData data : sourceDataList) {
            data.updateStatus(CrawledDataStatus.APPROVED, savedReferenceId);
        }
    }

    @Transactional
    public void approveAndPost(Long crawledDataId, String publishType, Long boardId, String kNewsTypeStr, String content,
                               List<String> selectedImageUrls, String mainThumbnailUrl, String popularThumbnailUrl,
                               MultipartFile mainThumbnailFile, MultipartFile popularThumbnailFile,
                               List<MultipartFile> contentImages) {

        CrawledData crawledData = crawledDataRepository.findById(crawledDataId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.CRAWLED_DATA_NOT_FOUND));
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        User adminUser = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        Long savedReferenceId = null;
        Map<String, String> uploadedUrlCache = new HashMap<>();

        if ("GENERAL".equals(publishType)) {
            if (boardId == null) throw new BusinessException(CommunityErrorCode.BOARD_NOT_FOUND);
            Board targetBoard = boardRepository.findById(boardId).orElseThrow(() -> new BusinessException(CommunityErrorCode.BOARD_NOT_FOUND));

            String mergedContent = "# " + crawledData.getTitle() + "\n\n" + content;
            Post newPost = new Post(mergedContent, adminUser, targetBoard);
            Post savedPost = postRepository.save(newPost);
            savedReferenceId = savedPost.getId();

            if (selectedImageUrls != null && !selectedImageUrls.isEmpty()) {
                postImageService.uploadAndSavePostImagesFromUrls(savedPost, selectedImageUrls);
            }

        } else if ("MAIN_PAGE".equals(publishType)) {
            KNewsContentType kNewsType = null;
            if (StringUtils.hasText(kNewsTypeStr)) {
                try {
                    kNewsType = KNewsContentType.valueOf(kNewsTypeStr);
                } catch (IllegalArgumentException e) {
                    throw new BusinessException(CommonErrorCode.INVALID_INPUT);
                }
            }

            MainPageContent newContent = MainPageContent.builder()
                    .title(crawledData.getTitle())
                    .htmlContent(content)
                    .type(kNewsType)
                    .originalUrl(crawledData.getOriginalUrl())
                    .build();

            MainPageContent savedContent = mainContentRepository.save(newContent);
            Long contentId = savedContent.getId();
            savedReferenceId = contentId;

            String processedHtml = processHtmlAndUploadImages(content, contentId, uploadedUrlCache, contentImages);
            savedContent.changeHtmlContent(processedHtml);

            processAndSaveThumbnail(contentId, mainThumbnailFile, mainThumbnailUrl, ImageType.MAIN_PAGE_THUMBNAIL, uploadedUrlCache);
            processAndSaveThumbnail(contentId, popularThumbnailFile, popularThumbnailUrl, ImageType.MAIN_PAGE_POPULAR_THUMBNAIL, uploadedUrlCache);
        }

        crawledData.updateStatus(CrawledDataStatus.APPROVED, savedReferenceId);
    }

    private void processAndSaveThumbnail(Long contentId, MultipartFile file, String url, ImageType type, Map<String, String> urlCache) {
        String cdnUrl = null;

        if (file != null && !file.isEmpty()) {
            cdnUrl = uploadFileToStorage(file, contentId);
        }
        else if (url != null && !url.isBlank()) {
            cdnUrl = urlCache.computeIfAbsent(url, k -> this.uploadUrlToStorage(k, contentId));
        }

        if (cdnUrl != null) {
            if (!imageRepository.existsByRelatedIdAndUrlAndImageType(contentId, cdnUrl, type)) {
                Image image = Image.of(type, contentId, cdnUrl, 0);
                imageRepository.save(image);
            }
        }
    }

    private String uploadFileToStorage(MultipartFile file, Long contentId) {
        String ext = getExtension(file.getOriginalFilename());
        String key = "main-page/" + contentId + "/" + UUID.randomUUID() + ext;

        imageStorageClient.upload(file, key);
        return imageStorageClient.generatePublicUrl(key);
    }

    private String uploadUrlToStorage(String originalUrl, Long contentId) {
        String ext = getExtension(originalUrl);
        String key = "main-page/" + contentId + "/" + UUID.randomUUID() + ext;

        String uploadedKey = imageStorageClient.uploadFromUrl(originalUrl, key);
        if (uploadedKey != null) {
            return imageStorageClient.generatePublicUrl(key);
        }
        return null;
    }

    /**
     * HTML 파싱 -> 이미지 태그 추출 -> S3 업로드 -> src 교체 -> DB 저장(MAIN_PAGE_BODY)
     */
    private String processHtmlAndUploadImages(String htmlContent, Long contentId,
                                              Map<String, String> uploadedUrlCache,
                                              List<MultipartFile> contentImages) {
        Document doc = Jsoup.parseBodyFragment(htmlContent);
        Elements imgTags = doc.select("img");

        int orderIndex = 0;
        for (Element img : imgTags) {
            String originalSrc = img.attr("src");
            String dataFileIndex = img.attr("data-file-index");

            if (StringUtils.hasText(dataFileIndex) && contentImages != null) {
                try {
                    int index = Integer.parseInt(dataFileIndex);
                    if (index >= 0 && index < contentImages.size()) {
                        MultipartFile file = contentImages.get(index);
                        if (file != null && !file.isEmpty()) {
                            String cdnUrl = uploadFileToStorage(file, contentId);

                            img.attr("src", cdnUrl);
                            img.removeAttr("data-file-index");

                            saveBodyImageEntity(contentId, cdnUrl, orderIndex++);
                        }
                    }
                } catch (NumberFormatException e) {
                    log.warn("Invalid data-file-index: {}", dataFileIndex);
                }
            }
            else if (originalSrc != null) {
                String cdnUrl = uploadedUrlCache.computeIfAbsent(originalSrc, k -> this.uploadUrlToStorage(k, contentId));

                if (cdnUrl != null) {
                    img.attr("src", cdnUrl);
                    saveBodyImageEntity(contentId, cdnUrl, orderIndex++);
                }
            }
        }

        return doc.body().html();
    }

    private void saveBodyImageEntity(Long contentId, String cdnUrl, int orderIndex) {
        if (!imageRepository.existsByRelatedIdAndUrlAndImageType(contentId, cdnUrl, ImageType.MAIN_PAGE_BODY)) {
            Image bodyImage = Image.of(ImageType.MAIN_PAGE_BODY, contentId, cdnUrl, orderIndex);
            imageRepository.save(bodyImage);
        }
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

    @Transactional
    public void rejectCrawledData(Long crawledDataId) {
        CrawledData crawledData = crawledDataRepository.findById(crawledDataId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.CRAWLED_DATA_NOT_FOUND));

        crawledData.updateStatus(CrawledDataStatus.REJECTED, null);
    }

    @Transactional(readOnly = true)
    public CrawledData getCrawledDataById(Long id) {
        return crawledDataRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.CRAWLED_DATA_NOT_FOUND));
    }

    @Transactional
    public long deleteCrawledDataBefore(LocalDate targetDate) {
        Instant threshold = targetDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();

        return crawledDataRepository.deleteByCrawledAtBefore(threshold);
    }
}

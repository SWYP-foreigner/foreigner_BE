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
import core.global.entity.image.service.PostImageService;
import core.global.enums.CrawledDataStatus;
import core.global.enums.ImageType;
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

    private final S3Client s3Client;
    private final S3Props s3Props;

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
    public void approveMergedData(List<Long> sourceIds, String title, String publishType, Long boardId, String content,
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
            MainPageContent newContent = MainPageContent.builder()
                    .title(title).htmlContent(content)
                    .originalUrl(sourceDataList.get(0).getOriginalUrl()).build();
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
    public void approveAndPost(Long crawledDataId, String publishType, Long boardId, String content,
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
            MainPageContent newContent = MainPageContent.builder()
                    .title(crawledData.getTitle()).htmlContent(content)
                    .originalUrl(crawledData.getOriginalUrl()).build();
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
        String s3Url = null;

        if (file != null && !file.isEmpty()) {
            s3Url = uploadMultipartFileToS3(file, contentId);
        }
        else if (url != null && !url.isBlank()) {
            s3Url = urlCache.computeIfAbsent(url, k -> this.uploadImageToS3(k, contentId));
        }

        if (s3Url != null) {
            if (!imageRepository.existsByRelatedIdAndUrlAndImageType(contentId, s3Url, type)) {
                Image image = Image.of(type, contentId, s3Url, 0);
                imageRepository.save(image);
            }
        }
    }

    private String uploadMultipartFileToS3(MultipartFile file, Long contentId) {
        try {
            String originalFilename = file.getOriginalFilename();
            String extension = ".jpg";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
            }

            String fileName = UUID.randomUUID() + extension;
            String s3Key = "main-page/" + contentId + "/" + fileName;

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(s3Props.getBucket())
                    .key(s3Key)
                    .contentType(file.getContentType())
                    .acl(ObjectCannedACL.PUBLIC_READ)
                    .contentLength(file.getSize())
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

            return s3Client.utilities().getUrl(GetUrlRequest.builder()
                    .bucket(s3Props.getBucket())
                    .key(s3Key)
                    .build()).toString();

        } catch (IOException e) {
            log.error("Failed to upload MultipartFile to S3", e);
            throw new BusinessException(CommonErrorCode.FILE_UPLOAD_ERROR);
        }
    }

    private void saveSpecificThumbnail(Long contentId, String originalUrl, ImageType type, Map<String, String> uploadedUrlCache) {
        String s3Url = uploadedUrlCache.computeIfAbsent(originalUrl, k -> this.uploadImageToS3(k, contentId));

        if (s3Url != null) {
            if (!imageRepository.existsByRelatedIdAndUrlAndImageType(contentId, s3Url, type)) {
                Image image = Image.of(type, contentId, s3Url, 0);
                imageRepository.save(image);
            }
        }
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
                            String s3Url = uploadMultipartFileToS3(file, contentId);

                            img.attr("src", s3Url);
                            img.removeAttr("data-file-index");

                            saveBodyImageEntity(contentId, s3Url, orderIndex++);
                        }
                    }
                } catch (NumberFormatException e) {
                    log.warn("Invalid data-file-index: {}", dataFileIndex);
                }
            }
            else if (originalSrc != null) {
                String s3Url = uploadedUrlCache.computeIfAbsent(originalSrc, k -> this.uploadImageToS3(k, contentId));

                if (s3Url != null) {
                    img.attr("src", s3Url);
                    saveBodyImageEntity(contentId, s3Url, orderIndex++);
                }
            }
        }

        return doc.body().html();
    }

    private void saveBodyImageEntity(Long contentId, String s3Url, int orderIndex) {
        if (!imageRepository.existsByRelatedIdAndUrlAndImageType(contentId, s3Url, ImageType.MAIN_PAGE_BODY)) {
            Image bodyImage = Image.of(ImageType.MAIN_PAGE_BODY, contentId, s3Url, orderIndex);
            imageRepository.save(bodyImage);
        }
    }

    private void saveMainPageImages(Long contentId, List<String> originalUrls, Map<String, String> uploadedUrlCache) {
        int limit = Math.min(originalUrls.size(), 5);

        for (int i = 0; i < limit; i++) {
            String originalUrl = originalUrls.get(i);

            String s3Url = uploadedUrlCache.computeIfAbsent(originalUrl, k -> this.uploadImageToS3(k, contentId));

            if (s3Url != null) {
                ImageType type = (i == 0) ? ImageType.MAIN_PAGE_THUMBNAIL : ImageType.MAIN_PAGE_POPULAR_THUMBNAIL;

                if (!imageRepository.existsByRelatedIdAndUrlAndImageType(contentId, s3Url, type)) {
                    Image image = Image.of(type, contentId, s3Url, i);
                    imageRepository.save(image);
                }
            }
        }
    }

    private void saveFirstImageAsThumbnail(Long contentId, String processedHtml) {
        Document doc = Jsoup.parseBodyFragment(processedHtml);
        Element firstImg = doc.select("img").first();

        if (firstImg != null) {
            String s3Url = firstImg.attr("src");

            if (!imageRepository.existsByRelatedIdAndUrlAndImageType(contentId, s3Url, ImageType.MAIN_PAGE_THUMBNAIL)) {
                Image thumbnail = Image.of(ImageType.MAIN_PAGE_THUMBNAIL, contentId, s3Url, 0);
                imageRepository.save(thumbnail);
            }
        }
    }

    /**
     * 외부 URL 이미지를 다운로드하여 S3에 업로드 (Public Read 권한 부여)
     */
    private String uploadImageToS3(String imageUrl, Long contentId) {
        try {
            URL url = new URL(imageUrl);
            String extension = getExtensionFromUrl(imageUrl);
            String fileName = UUID.randomUUID() + extension;
            String s3Key = "main-page/" + contentId + "/" + fileName;

            try (InputStream inputStream = url.openStream()) {

                byte[] imageBytes = inputStream.readAllBytes();

                PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                        .bucket(s3Props.getBucket())
                        .key(s3Key)
                        .contentType("image/" + (extension.equals(".png") ? "png" : "jpeg"))
                        .acl(ObjectCannedACL.PUBLIC_READ)
                        .contentLength((long) imageBytes.length)
                        .build();

                s3Client.putObject(putObjectRequest, RequestBody.fromBytes(imageBytes));
            }

            return s3Client.utilities().getUrl(GetUrlRequest.builder()
                    .bucket(s3Props.getBucket())
                    .key(s3Key)
                    .build()).toString();

        } catch (Exception e) {
            log.error("Failed to upload image from URL: {}", imageUrl, e);
            return null;
        }
    }

    private String getExtensionFromUrl(String url) {
        int lastDotIndex = url.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < url.length() - 1) {
            String ext = url.substring(lastDotIndex).toLowerCase();
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

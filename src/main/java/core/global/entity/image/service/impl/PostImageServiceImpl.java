package core.global.entity.image.service.impl;

import core.domain.post.entity.Post;
import core.global.entity.image.S3Props;
import core.global.entity.image.dto.PresignedUrlRequest;
import core.global.entity.image.dto.PresignedUrlResponse;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageStorageClient;
import core.global.entity.image.service.PostImageService;
import core.global.entity.image.utils.UrlUtil;
import core.global.enums.ImageModerationStatus;
import core.global.enums.ImageType;
import core.global.enums.errorcode.ImageErrorCode;
import core.global.exception.BusinessException;
import core.global.service.ContentModerationService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

import static core.global.entity.image.utils.UrlUtil.buildCdnUrlFromKey;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostImageServiceImpl implements PostImageService {

    private final S3Client s3Client;
    private final ImageRepository imageRepository;
    private final ImageStorageClient storageClient;
    private final S3Presigner s3Presigner;
    private final S3Props s3Props;
    private final ContentModerationService contentModerationService;

    @Value("${ncp.s3.bucket}")
    private String bucket;
    @Value("${ncp.s3.endpoint}")
    private String endPoint;
    @Value("${cdn.base-url}")
    private String cdnBaseUrl;

    /**
     * ✅ Presigned URL 생성 (일괄)
     */
    @Override
    public List<PresignedUrlResponse> generatePresignedUrls(PresignedUrlRequest request) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        if (request.files() == null || request.files().isEmpty()) {
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        }
        if (request.uploadSessionId() == null || request.uploadSessionId().isBlank()) {
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        }


        List<PresignedUrlResponse> out = new ArrayList<>(request.files().size());
        for (PresignedUrlRequest.FileSpec f : request.files()) {
            out.add(generateOne(email, request.imageType(), request.uploadSessionId(), f));
        }
        return out;
    }

    private PresignedUrlResponse generateOne(
            String email,
            ImageType imageType,
            String uploadSessionId,
            PresignedUrlRequest.FileSpec fileSpec
    ) {
        String filename = fileSpec.filename();
        String contentType = (fileSpec.contentType() == null || fileSpec.contentType().isBlank())
                ? "image/jpeg"
                : fileSpec.contentType();

        String key = UrlUtil.buildRawKey(email, imageType, uploadSessionId, filename);

        // 서명에 포함할 메타데이터
        Map<String, String> meta = Map.of(
                "owner", email,
                "session", uploadSessionId,
                "image-type", imageType.name().toLowerCase()
        );

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .metadata(meta)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .putObjectRequest(putObjectRequest)
                .signatureDuration(Duration.ofMinutes(10))
                .build();

        var presigned = s3Presigner.presignPutObject(presignRequest);

        // 클라이언트가 그대로 써야 하는 헤더
        Map<String, String> clientHeaders = new LinkedHashMap<>();
        clientHeaders.put("Content-Type", contentType);
        clientHeaders.put("x-amz-meta-owner", email);
        clientHeaders.put("x-amz-meta-session", uploadSessionId);
        clientHeaders.put("x-amz-meta-image-type", imageType.name().toLowerCase());

        String publicUrl = UrlUtil.buildPublicUrlFromKey(endPoint, bucket, key);

        return new PresignedUrlResponse(
                key,
                presigned.url().toString(),
                "PUT",
                clientHeaders
        );
    }

    @Override
    @Transactional
    public void savePostImages(Long postId, List<String> toAdd) throws BusinessException {
        final List<String> adds = normalizeList(toAdd);
        if (adds.isEmpty()) return;

        // 1) 이미지가 존재하면 예외
        if (imageRepository.existsByImageTypeAndRelatedId(ImageType.POST, postId)) {
            throw new BusinessException(ImageErrorCode.POST_IMAGES_ALREADY_EXIST);
        }

        final String basePrefix = "posts/" + postId;

        // 3) 병렬 COPY (스테이징 원본은 목록에 모아 한 번에 삭제)
        CopyResult copyResult = copyNewImagesInParallel(
                postId,
                adds,
                basePrefix,
                Collections.emptySet(),
                0
        );

        // 4) DB 저장
        if (!copyResult.toSave().isEmpty()) {
            imageRepository.saveAll(copyResult.toSave());
        }

        // 스테이징 원본 삭제
        if (!copyResult.stagingToDelete().isEmpty()) {
            storageClient.deleteObjectsBulk(copyResult.stagingToDelete());
        }
    }

    @Override
    @Transactional
    public void updatePostImages(Long postId, List<String> toAdd, List<String> toRemove) {
        final List<String> adds = normalizeList(toAdd);
        final List<String> removes = normalizeList(toRemove);
        if (adds.isEmpty() && removes.isEmpty()) return;

        // 1) DB 삭제 + 삭제 대상 키 수집(사용자 제거)
        List<String> bulkDeleteKeys = deleteRemovedImagesAndCollectKeys(postId, removes);

        // 2) 생존 이미지 조회 + position 재정렬 + 생존 URL 집합 생성
        SurvivorContext survivorContext = loadAndReorderSurvivors(postId);

        if (adds.isEmpty()) {
            // 추가할 게 없으면 여기서 삭제 끝내고 종료
            storageClient.deleteObjectsBulk(bulkDeleteKeys);
            return;
        }

        final String basePrefix = "posts/" + postId;

        // 3) 병렬 COPY (스테이징 원본은 목록에 모아 한 번에 삭제)
        CopyResult copyResult = copyNewImagesInParallel(
                postId,
                adds,
                basePrefix,
                survivorContext.survivorUrls(),
                survivorContext.nextPosition()
        );

        // 4) DB 저장
        if (!copyResult.toSave().isEmpty()) {
            imageRepository.saveAll(copyResult.toSave());
        }

        // 5) S3 삭제(사용자 제거 + 스테이징 원본)
        bulkDeleteKeys.addAll(copyResult.stagingToDelete());
        storageClient.deleteObjectsBulk(bulkDeleteKeys);
    }

    @Override
    @Transactional
    public void uploadAndSavePostImages(Post post, List<MultipartFile> multipartFiles) throws IOException {

        if (multipartFiles == null || multipartFiles.isEmpty()) return;

        List<Image> newImages = new ArrayList<>();
        int orderIndex = 0;

        for (MultipartFile file : multipartFiles) {
            if (file.isEmpty()) continue;

            ContentModerationService.ModerationResult result = contentModerationService.inspectImage(file);
            ImageModerationStatus status = result.isHarmful() ? ImageModerationStatus.SUSPICIOUS : ImageModerationStatus.CLEAN;
            String reason = result.getReason();

            String originalFileName = file.getOriginalFilename();
            String extension = "";
            if (originalFileName != null && originalFileName.contains(".")) {
                extension = originalFileName.substring(originalFileName.lastIndexOf("."));
            }
            String uniqueFileName = UUID.randomUUID() + extension;
            String s3Key = "post-images/" + post.getId() + "/" + uniqueFileName;

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(s3Props.getBucket())
                    .key(s3Key)
                    .contentType(file.getContentType())
                    .acl(ObjectCannedACL.PUBLIC_READ)
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

            String candidateFinalUrl = UrlUtil.buildPublicUrlFromKey(
                    s3Props.getEndPoint(),
                    s3Props.getBucket(),
                    s3Key
            );

            Image image = Image.of(
                    ImageType.POST,
                    post.getId(),
                    candidateFinalUrl,
                    orderIndex++,
                    status,
                    reason
            );
            newImages.add(image);
        }

        imageRepository.saveAll(newImages);
    }

    private List<String> normalizeList(List<String> list) {
        return (list == null) ? List.of() : list;
    }

    private CopyResult copyNewImagesInParallel(
            Long postId,
            List<String> adds,
            String basePrefix,
            Set<String> survivorUrls,
            int startOrder
    ) {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(
                Math.min(Math.max(1, adds.size()), 8)
        );

        var tasks = new ArrayList<java.util.concurrent.Callable<Image>>();
        var stagingToDelete = new java.util.concurrent.ConcurrentLinkedQueue<String>();

        for (int i = 0; i < adds.size(); i++) {
            final int myOrder = startOrder + i;
            final String raw = adds.get(i);

            tasks.add(() -> {
                String srcKey = UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, raw);

                // 1) 기본 이미지인 경우
                if (isDefaultUrlOrKey(srcKey)) {
                    String finalUrl = UrlUtil.buildCdnUrlFromKey(cdnBaseUrl, srcKey);
                    if (survivorUrls.contains(finalUrl)) return null;
                    return Image.of(ImageType.POST, postId, finalUrl, myOrder);
                }

                // 2) 일반/스테이징 이미지인 경우
                String finalKey = ensureFinalKey(basePrefix, myOrder, srcKey);
                String finalUrl = UrlUtil.buildCdnUrlFromKey(cdnBaseUrl, finalKey);
                if (survivorUrls.contains(finalUrl)) return null;

                // 스테이징이면 COPY 성공 후 원본 삭제 후보에 추가
                if (storageClient.isStagingKey(srcKey) && !srcKey.equals(finalKey) && !isDefaultUrlOrKey(srcKey)) {
                    stagingToDelete.add(srcKey);
                }

                return Image.of(ImageType.POST, postId, finalUrl, myOrder);
            });
        }

        List<Image> toSave = new ArrayList<>();
        try {
            for (var f : pool.invokeAll(tasks)) {
                Image created = f.get();
                if (created != null) toSave.add(created);
            }
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        } finally {
            pool.shutdown();
        }

        return new CopyResult(toSave, new ArrayList<>(stagingToDelete));
    }

    private List<String> deleteRemovedImagesAndCollectKeys(Long postId, List<String> removes) {
        List<String> bulkDeleteKeys = new ArrayList<>();
        if (!removes.isEmpty()) {
            List<String> removeKeys = removes.stream()
                    .map(raw -> UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, raw))
                    .toList();
            List<String> removeUrls = removeKeys.stream()
                    .map(k -> UrlUtil.buildCdnUrlFromKey(cdnBaseUrl, k))
                    .toList();
            imageRepository.deleteByImageTypeAndRelatedIdAndUrlIn(ImageType.POST, postId, removeUrls);

            bulkDeleteKeys.addAll(
                    removeKeys.stream().filter(k -> !isDefaultUrlOrKey(k)).toList()
            );
        }
        return bulkDeleteKeys;
    }

    private SurvivorContext loadAndReorderSurvivors(Long postId) {
        List<Image> survivors = imageRepository
                .findByImageTypeAndRelatedIdOrderByPositionAsc(ImageType.POST, postId);

        int pos = 0;
        Set<String> survivorUrls = new HashSet<>();
        for (Image img : survivors) {
            img.changePosition(pos++);

            String storedKey = UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, img.getUrl());
            String finalUrl = UrlUtil.buildCdnUrlFromKey(cdnBaseUrl, storedKey);
            survivorUrls.add(finalUrl);
        }

        return new SurvivorContext(survivors, survivorUrls, pos);
    }

    private boolean isDefaultUrlOrKey(String keyOrUrl) {
        if (keyOrUrl == null || keyOrUrl.isBlank()) return false;
        String k = UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, keyOrUrl);
        k = UrlUtil.trimSlashes(k);
        return k.startsWith("default/"); // 예: default/character_03.png
    }

    private String ensureFinalKey(String basePrefix, int order, String srcKey) {
        String base = basePrefix.endsWith("/") ? basePrefix.substring(0, basePrefix.length() - 1) : basePrefix;
        if (!storageClient.isStagingKey(srcKey)) return srcKey;

        String basename = srcKey.substring(srcKey.lastIndexOf('/') + 1);
        String dstKey = "%s/%03d_%s".formatted(base, order, basename);
        if (srcKey.equals(dstKey)) return srcKey;

        try {
            s3Client.copyObject(b -> b
                    .sourceBucket(bucket).sourceKey(srcKey)
                    .destinationBucket(bucket).destinationKey(dstKey)
                    .acl(ObjectCannedACL.PUBLIC_READ)
                    .metadataDirective(MetadataDirective.COPY)
            );
        } catch (S3Exception e) {
            log.warn("[POST IMG] copy failed: src={}, dst={}, status={}, msg={}",
                    srcKey, dstKey, e.statusCode(),
                    e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage());
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        } catch (SdkException e) {
            log.warn("[POST IMG] copy failed: src={}, dst={}, err={}", srcKey, dstKey, e.getMessage());
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        }
        return dstKey; // ← 여기서 삭제하지 않음
    }

    private record SurvivorContext(
            List<Image> survivors,
            Set<String> survivorUrls,
            int nextPosition
    ) {
    }

    private record CopyResult(
            List<Image> toSave,
            List<String> stagingToDelete
    ) {
    }
}
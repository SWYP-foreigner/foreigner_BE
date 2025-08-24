package core.global.image.service.impl;

import core.global.enums.ErrorCode;
import core.global.enums.ImageType;
import core.global.exception.BusinessException;
import core.global.image.dto.PresignedUrlRequest;
import core.global.image.dto.PresignedUrlResponse;
import core.global.image.entity.Image;
import core.global.image.repository.ImageRepository;
import core.global.image.service.ImageService;
import core.global.image.utils.UrlUtil;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageServiceImpl implements ImageService {

    private final S3Presigner s3Presigner;
    private final S3Client s3Client;
    private final ImageRepository imageRepository;

    @Value("${ncp.s3.bucket}")
    private String bucket;
    @Value("${ncp.s3.end-point}")
    private String endPoint;

    private static String extOf(String key) {
        int dot = key.lastIndexOf('.');
        String ext = (dot > -1 && dot < key.length() - 1) ? key.substring(dot + 1) : "jpg";
        if (ext.length() > 8) ext = "jpg";
        return ext.toLowerCase();
    }

    /**
     * ✅ Presigned URL 생성 (일괄)
     */
    @Override
    public List<PresignedUrlResponse> generatePresignedUrls(String username, PresignedUrlRequest request) {
        if (request.files() == null || request.files().isEmpty()) {
            throw new BusinessException(ErrorCode.IMAGE_FILE_UPLOAD_FAILED);
        }
        if (request.uploadSessionId() == null || request.uploadSessionId().isBlank()) {
            throw new BusinessException(ErrorCode.IMAGE_FILE_UPLOAD_FAILED);
        }


        List<PresignedUrlResponse> out = new ArrayList<>(request.files().size());
        for (PresignedUrlRequest.FileSpec f : request.files()) {
            out.add(generateOne(username, request.imageType(), request.uploadSessionId(), f));
        }
        return out;
    }

    private PresignedUrlResponse generateOne(
            String username,
            ImageType imageType,
            String uploadSessionId,
            PresignedUrlRequest.FileSpec fileSpec
    ) {
        String filename = fileSpec.filename();
        String contentType = (fileSpec.contentType() == null || fileSpec.contentType().isBlank())
                ? "image/jpeg"
                : fileSpec.contentType();

        String key = UrlUtil.buildRawKey(username, imageType, uploadSessionId, filename);

        // 서명에 포함할 메타데이터
        Map<String, String> meta = Map.of(
                "owner", username,
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
        clientHeaders.put("x-amz-meta-owner", username);
        clientHeaders.put("x-amz-meta-session", uploadSessionId);
        clientHeaders.put("x-amz-meta-image-type", imageType.name().toLowerCase());

        String publicUrl = UrlUtil.buildPublicUrlFromKey(endPoint, bucket, key);

        return new PresignedUrlResponse(
                key,
                presigned.url().toString(),
                "PUT",
                clientHeaders,
                publicUrl
        );
    }

    @Transactional
    @Override
    public void saveOrUpdatePostImages(Long postId,
                                       List<String> toAdd,
                                       List<String> toRemove) {

        final List<String> adds = (toAdd == null) ? List.of() : toAdd;
        final List<String> removes = (toRemove == null) ? List.of() : toRemove;

        // 아무것도 없으면 바로 반환
        if (adds.isEmpty() && removes.isEmpty()) {
            return;
        }

        // 1) 삭제 처리 (DB + S3)
        if (!removes.isEmpty()) {
            // URL → key 변환
            List<String> removeKeys = removes.stream()
                    .map(url -> UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, url))
                    .toList();

            // DB에서 삭제
            imageRepository.deleteByImageTypeAndRelatedIdAndUrlIn(ImageType.POST, postId, removeKeys);

            // S3에서도 삭제 시도
            for (String key : removeKeys) {
                try {
                    s3Client.deleteObject(DeleteObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .build());
                } catch (SdkException e) {
                    // 실패 시 로그만 남기고 무시 (DB와 불일치 허용)
                    log.warn("이미지 삭제 실패: key={}, err={}", key, e.getMessage());
                }
            }
        }

        // 2) 생존 이미지 조회 & 순서 재정렬
        List<Image> survivors = imageRepository
                .findByImageTypeAndRelatedIdOrderByPositionAsc(ImageType.POST, postId);

        int pos = 0;
        Set<String> survivorKeys = new HashSet<>();
        for (Image img : survivors) {
            img.changePosition(pos++);
            survivorKeys.add(img.getUrl());
        }

        // 3) 추가할 이미지 처리 (중복 제외 + temp/* → 최종키 COPY)
        List<Image> toSave = new ArrayList<>();
        for (String raw : adds) {
            String key = UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, raw);

            // 이미 존재하면 스킵
            if (survivorKeys.contains(key)) continue;

            // temp/*면 최종 경로로 COPY 후 원본 삭제
            String finalKey = ensureFinalKey("posts/post/" + postId, pos, key);
            Image created = Image.of(ImageType.POST, postId, finalKey, pos++);
            toSave.add(created);
        }

        // 4) 추가된 엔티티 저장
        if (!toSave.isEmpty()) {
            imageRepository.saveAll(toSave);
        }
    }


    private boolean isStagingKey(String key) {
        String k = UrlUtil.trimSlashes(key);
        return k.startsWith("images/");
    }

    private String ensureFinalKey(String basePrefix, int order, String srcKey) {
        String base = basePrefix.endsWith("/") ? basePrefix.substring(0, basePrefix.length() - 1) : basePrefix;

        if (isStagingKey(srcKey)) {
            String basename = srcKey.substring(srcKey.lastIndexOf('/') + 1);
            String dstKey = "%s/%03d_%s".formatted(base, order, basename);
            try {
                s3Client.copyObject(b -> b
                        .sourceBucket(bucket).sourceKey(srcKey)
                        .destinationBucket(bucket).destinationKey(dstKey));
                s3Client.deleteObject(b -> b.bucket(bucket).key(srcKey));
            } catch (SdkException e) {
                throw new BusinessException(ErrorCode.IMAGE_FILE_UPLOAD_FAILED);
            }
            return dstKey;
        }

        return srcKey;
    }

    private boolean existsOnS3(String key) {
        try {
            s3Client.headObject(b -> b.bucket(bucket).key(key));
            return true;
        } catch (S3Exception e) {
            return false;
        } catch (SdkException e) {
            log.warn("HEAD fail: key={}, err={}", key, e.getMessage());
            return false;
        }
    }

    /**
     * ✅ 단일 객체 삭제
     */
    @Override
    public void deleteObject(String keyOrUrl) {
        String key = UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, keyOrUrl);

        boolean exists = existsOnS3(key);
        log.info("[DEL][ONE] key={}, existsBefore={}", key, exists);
        if (!exists) {
            throw new BusinessException(ErrorCode.IMAGE_FILE_DELETE_FAILED);
        }

        try {
            s3Client.deleteObject(b -> b.bucket(bucket).key(key));
        } catch (SdkException e) {
            throw new BusinessException(ErrorCode.IMAGE_FILE_DELETE_FAILED);
        }
    }

    /**
     * ✅ 폴더 삭제 (prefix 기준)
     */
    @Override
    public void deleteFolder(String fileLocation) {
        String prefix = UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, fileLocation);
        if (!prefix.endsWith("/")) prefix += "/";

        String continuation = null;
        try {
            do {
                var reqBuilder = ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(prefix);

                if (continuation != null) {
                    reqBuilder.continuationToken(continuation);
                }

                var res = s3Client.listObjectsV2(reqBuilder.build());

                var toDelete = res.contents().stream()
                        .filter(o -> !o.key().endsWith("/"))
                        .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                        .collect(Collectors.toList());

                if (!toDelete.isEmpty()) {
                    var delReq = DeleteObjectsRequest.builder()
                            .bucket(bucket)
                            .delete(Delete.builder().objects(toDelete).build())
                            .build();
                    s3Client.deleteObjects(delReq);
                }

                continuation = res.isTruncated() ? res.nextContinuationToken() : null;
            } while (continuation != null);

        } catch (SdkException e) {
            throw new BusinessException(ErrorCode.IMAGE_FOLDER_DELETE_FAILED);
        }
    }

    @Override
    @Transactional
    public String upsertUserProfileImage(Long userId, String requestedKeyOrUrl) {
        if (requestedKeyOrUrl == null || requestedKeyOrUrl.isBlank()) {
            throw new BusinessException(ErrorCode.IMAGE_FILE_UPLOAD_FAILED);
        }

        String reqKey = UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, requestedKeyOrUrl);
        // 존재/타입/용량 검증 (10MB 예시)
        validateImageHeadOrThrow(reqKey, 10L * 1024 * 1024);

        // 기존 프로필 전부 제거 (USER, relatedId=userId)
        imageRepository.findByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, userId)
                .forEach(img -> {
                    try {
                        s3Client.deleteObject(b -> b.bucket(bucket).key(img.getUrl()));
                    } catch (SdkException e) {
                        log.warn("delete old profile key ignored: {}", e.getMessage());
                    }
                });
        imageRepository.deleteByImageTypeAndRelatedId(ImageType.USER, userId);

        String finalKey = reqKey;
        if (isStagingKey(reqKey)) {
            String ext = extOf(reqKey);
            String dstKey = "users/%d/profile.%s".formatted(userId, ext);
            try {
                s3Client.copyObject(b -> b.sourceBucket(bucket).sourceKey(reqKey)
                        .destinationBucket(bucket).destinationKey(dstKey));
                s3Client.deleteObject(b -> b.bucket(bucket).key(reqKey));
            } catch (SdkException e) {
                throw new BusinessException(ErrorCode.IMAGE_FILE_UPLOAD_FAILED);
            }
            finalKey = dstKey;
        }

        // 새 Image 레코드(프로필은 항상 orderIndex=0)
        imageRepository.save(Image.of(ImageType.USER, userId, finalKey, 0));
        return finalKey;
    }

    @Override
    @Transactional
    public void deleteUserProfileImage(Long userId) {
        List<Image> images = imageRepository.findByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, userId);
        if (images.isEmpty()) return;

        for (Image img : images) {
            try {
                s3Client.deleteObject(b -> b.bucket(bucket).key(img.getUrl()));
            } catch (SdkException e) {
                log.warn("profile key delete failed (ignored): {}", e.getMessage());
            }
        }
        imageRepository.deleteByImageTypeAndRelatedId(ImageType.USER, userId);
    }

    @Override
    public String getUserProfileKey(Long userId) {
        return imageRepository
                .findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, userId)
                .map(Image::getUrl)
                .orElse(null);
    }

    @Override
    public String normalizeKey(String keyOrUrl) {
        return (keyOrUrl == null || keyOrUrl.isBlank()) ? null
                : UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, keyOrUrl);
    }

    @Override
    public String toPublicUrl(String keyOrNull) {
        if (keyOrNull == null || keyOrNull.isBlank()) return null;
        return UrlUtil.buildPublicUrlFromKey(endPoint, bucket, keyOrNull);
    }

    // 내부 검증/확장자 유틸 (이미 클래스에 없다면 추가)
    private void validateImageHeadOrThrow(String key, long maxBytes) {
        HeadObjectResponse head;
        try {
            head = s3Client.headObject(b -> b.bucket(bucket).key(key));
        } catch (SdkException e) {
            throw new BusinessException(ErrorCode.IMAGE_FILE_UPLOAD_FAILED);
        }
        long size = head.contentLength();
        if (size <= 0 || size > maxBytes) throw new BusinessException(ErrorCode.IMAGE_FILE_UPLOAD_FAILED);
        String ct = Optional.ofNullable(head.contentType()).orElse("").toLowerCase();
        if (!ct.startsWith("image/")) throw new BusinessException(ErrorCode.IMAGE_FILE_UPLOAD_TYPE_ERROR);
    }

}

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
import org.springframework.security.core.context.SecurityContextHolder;
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
    @Value("${ncp.s3.endpoint}")
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
    public List<PresignedUrlResponse> generatePresignedUrls(PresignedUrlRequest request) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();

        if (request.files() == null || request.files().isEmpty()) {
            throw new BusinessException(ErrorCode.IMAGE_UPLOAD_FAILED);
        }
        if (request.uploadSessionId() == null || request.uploadSessionId().isBlank()) {
            throw new BusinessException(ErrorCode.IMAGE_UPLOAD_FAILED);
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

    @Transactional
    @Override
    public void saveOrUpdatePostImages(Long postId,
                                       List<String> toAdd,
                                       List<String> toRemove) {

        final List<String> adds = (toAdd == null) ? List.of() : toAdd;
        final List<String> removes = (toRemove == null) ? List.of() : toRemove;

        if (adds.isEmpty() && removes.isEmpty()) {
            return;
        }

        // 4) 삭제: DB는 URL 기준, S3는 key 기준 ------------- [변경]
        if (!removes.isEmpty()) {
            // 입력이 URL/Key 섞여 와도 key로 정규화
            List<String> removeKeys = removes.stream()
                    .map(raw -> UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, raw))
                    .toList();

            // DB 삭제는 "공개 URL" 기준으로
            List<String> removeUrls = removeKeys.stream()
                    .map(k -> UrlUtil.buildPublicUrlFromKey(endPoint, bucket, k))
                    .toList();
            imageRepository.deleteByImageTypeAndRelatedIdAndUrlIn(ImageType.POST, postId, removeUrls);

            // S3 삭제는 key 기준
            for (String key : removeKeys) {
                try {
                    s3Client.deleteObject(b -> b.bucket(bucket).key(key));
                } catch (SdkException e) {
                    log.warn("이미지 삭제 실패: key={}, err={}", key, e.getMessage());
                }
            }
        }

        // 생존 이미지 조회 & 순서 재정렬
        List<Image> survivors = imageRepository
                .findByImageTypeAndRelatedIdOrderByPositionAsc(ImageType.POST, postId);

        int pos = 0;

        Set<String> survivorUrls = new HashSet<>();
        for (Image img : survivors) {
            img.changePosition(pos++);
            // DB에 key/URL 혼재 가능 → 모두 key로 환산 후 다시 공개 URL로 통일
            String storedKey = UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, img.getUrl());
            String storedUrl = UrlUtil.buildPublicUrlFromKey(endPoint, bucket, storedKey);
            survivorUrls.add(storedUrl);
        }

        List<Image> toSave = new ArrayList<>();
        for (String raw : adds) {
            String key = UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, raw);
            boolean staging = isStagingKey(key);
            boolean exists = staging && existsOnS3(key);

            log.info("[POST IMG] postId={}, inRaw={}, normKey={}, staging={}, exists={}",
                    postId, raw, key, staging, exists);

            // 최종 목적지 키 생성 (order 반영)
            String finalKey = ensureFinalKey("posts/" + postId, pos, key);
            String finalUrl = UrlUtil.buildPublicUrlFromKey(endPoint, bucket, finalKey);

            if (survivorUrls.contains(finalUrl)) {
                log.info("[POST IMG] skip (already attached): {}", finalUrl);
                continue;
            }

            Image created = Image.of(ImageType.POST, postId, finalUrl, pos++);
            toSave.add(created);
        }

        if (!toSave.isEmpty()) {
            imageRepository.saveAll(toSave);
        }
    }


    private boolean isStagingKey(String key) {
        String k = UrlUtil.trimSlashes(key);
        return k.startsWith("temp/");
    }

    private String ensureFinalKey(String basePrefix, int order, String srcKey) {
        String base = basePrefix.endsWith("/") ? basePrefix.substring(0, basePrefix.length() - 1) : basePrefix;

        try {
            s3Client.headObject(b -> b.bucket(bucket).key(srcKey));
        } catch (S3Exception e) {
            log.warn("[POST IMG] source not found for copy. key={}, status={}", srcKey, e.statusCode());
            throw new BusinessException(ErrorCode.IMAGE_UPLOAD_FAILED);
        } catch (SdkException e) {
            log.warn("[POST IMG] headObject failed. key={}, err={}", srcKey, e.getMessage());
            throw new BusinessException(ErrorCode.IMAGE_UPLOAD_FAILED);
        }

        // 2) 스테이징 여부 확인 (images/로 시작하면 스테이징)
        if (!isStagingKey(srcKey)) {
            log.info("[POST IMG] already final. key={}", srcKey);
            return srcKey;
        }

        String basename = srcKey.substring(srcKey.lastIndexOf('/') + 1);
        String dstKey = "%s/%03d_%s".formatted(base, order, basename);

        // 3) 복사 + 삭제
        try {
            log.info("[POST IMG] copy: src={} -> dst={}", srcKey, dstKey);
            s3Client.copyObject(b -> b
                    .sourceBucket(bucket).sourceKey(srcKey)
                    .destinationBucket(bucket).destinationKey(dstKey)
                    .acl(ObjectCannedACL.PUBLIC_READ)               // ✅ 새 오브젝트에 퍼블릭 읽기 부여
                    .metadataDirective(MetadataDirective.COPY)      // ✅ 메타/Content-Type 유지(명시)
            );
            s3Client.deleteObject(b -> b.bucket(bucket).key(srcKey));
        } catch (SdkException e) {
            log.warn("[POST IMG] copy/delete failed. src={}, dst={}, err={}", srcKey, dstKey, e.getMessage());
            throw new BusinessException(ErrorCode.IMAGE_UPLOAD_FAILED);
        }
        return dstKey;
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

        log.info(prefix);

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
            throw new BusinessException(ErrorCode.IMAGE_UPLOAD_FAILED);
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
                        .destinationBucket(bucket).destinationKey(dstKey)
                        .acl(ObjectCannedACL.PUBLIC_READ)               // ✅ 새 오브젝트에 퍼블릭 읽기 부여
                        .metadataDirective(MetadataDirective.COPY)      // ✅ 메타/Content-Type 유지(명시)
                );
                s3Client.deleteObject(b -> b.bucket(bucket).key(reqKey));
            } catch (SdkException e) {
                throw new BusinessException(ErrorCode.IMAGE_UPLOAD_FAILED);
            }
            finalKey = dstKey;
        }

        // 새 Image 레코드(프로필은 항상 orderIndex=0)
        String finalUrl = UrlUtil.buildPublicUrlFromKey(endPoint, bucket, finalKey);
        imageRepository.save(Image.of(ImageType.USER, userId, finalUrl, 0));
        return finalUrl;
    }

    @Override
    @Transactional
    public void deleteUserProfileImage(Long userId) {

        String folder = "users/%d/".formatted(userId);
        try {
            // 같은 클래스 내에 deleteFolder가 있다면 그대로 호출
            deleteFolder(folder);
        } catch (BusinessException e) {
            // 폴더 삭제 실패는 경고만 남기고, 아래 레거시 개별 삭제도 시도
            log.warn("profile folder delete failed (ignored): {}", e.getMessage());
        }

        imageRepository.deleteByImageTypeAndRelatedId(ImageType.USER, userId);
    }

    @Transactional
    @Override
    public String upsertChatRoomProfileImage(Long chatRoomId, String requestedKeyOrUrl) {
        if (requestedKeyOrUrl == null || requestedKeyOrUrl.isBlank()) {
            throw new BusinessException(ErrorCode.IMAGE_UPLOAD_FAILED);
        }

        String reqKey = UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, requestedKeyOrUrl);
        // 존재/타입/용량 검증 (10MB 예시)
        validateImageHeadOrThrow(reqKey, 10L * 1024 * 1024);

        // 기존 프로필 전부 제거 (USER, relatedId=userId)
        imageRepository.findByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.CHAT_ROOM, chatRoomId)
                .forEach(img -> {
                    try {
                        s3Client.deleteObject(b -> b.bucket(bucket).key(img.getUrl()));
                    } catch (SdkException e) {
                        log.warn("delete old profile key ignored: {}", e.getMessage());
                    }
                });
        imageRepository.deleteByImageTypeAndRelatedId(ImageType.CHAT_ROOM, chatRoomId);

        String finalKey = reqKey;
        if (isStagingKey(reqKey)) {
            String ext = extOf(reqKey);
            String dstKey = "chatRoom/%d/chat_profile.%s".formatted(chatRoomId, ext);
            try {
                s3Client.copyObject(b -> b.sourceBucket(bucket).sourceKey(reqKey)
                        .destinationBucket(bucket).destinationKey(dstKey)
                        .acl(ObjectCannedACL.PUBLIC_READ)               // ✅ 새 오브젝트에 퍼블릭 읽기 부여
                        .metadataDirective(MetadataDirective.COPY)      // ✅ 메타/Content-Type 유지(명시)
                );
                s3Client.deleteObject(b -> b.bucket(bucket).key(reqKey));
            } catch (SdkException e) {
                throw new BusinessException(ErrorCode.IMAGE_UPLOAD_FAILED);
            }
            finalKey = dstKey;
        }

        // 새 Image 레코드(프로필은 항상 orderIndex=0)
        String finalUrl = UrlUtil.buildPublicUrlFromKey(endPoint, bucket, finalKey);
        imageRepository.save(Image.of(ImageType.CHAT_ROOM, chatRoomId, finalUrl, 0));
        return finalUrl;
    }

    @Transactional
    @Override
    public void deleteChatRoomProfileImage(Long chatRoomId) {

        String folder = "chatRoom/%d/".formatted(chatRoomId);
        try {
            // 같은 클래스 내에 deleteFolder가 있다면 그대로 호출
            deleteFolder(folder);
        } catch (BusinessException e) {
            // 폴더 삭제 실패는 경고만 남기고, 아래 레거시 개별 삭제도 시도
            log.warn("profile folder delete failed (ignored): {}", e.getMessage());
        }

        imageRepository.deleteByImageTypeAndRelatedId(ImageType.CHAT_ROOM, chatRoomId);
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
            throw new BusinessException(ErrorCode.IMAGE_UPLOAD_FAILED);
        }
        long size = head.contentLength();
        if (size <= 0 || size > maxBytes) throw new BusinessException(ErrorCode.IMAGE_UPLOAD_FAILED);
        String ct = Optional.ofNullable(head.contentType()).orElse("").toLowerCase();
        if (!ct.startsWith("image/")) throw new BusinessException(ErrorCode.IMAGE_FILE_UPLOAD_TYPE_ERROR);
    }

}

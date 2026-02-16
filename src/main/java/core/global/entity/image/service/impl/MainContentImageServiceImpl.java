package core.global.entity.image.service.impl;

import core.global.entity.image.dto.ImageModerationEvent;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageStorageClient;
import core.global.entity.image.utils.UrlUtil;
import core.global.enums.ImageModerationStatus;
import core.global.enums.PollType;
import core.global.enums.common.ImageType;
import core.global.enums.errorcode.ImageErrorCode;
import core.global.exception.BusinessException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

@Slf4j
@Service
@RequiredArgsConstructor
public class MainContentImageServiceImpl implements MainContentImageService {

    private final S3Client s3Client;
    private final ImageRepository imageRepository;
    private final ImageStorageClient storageClient;
    private final ApplicationEventPublisher eventPublisher;
    
    @Qualifier("imageExecutor")
    private final Executor imageExecutor;
    @Value("${ncp.s3.bucket}")
    private String bucket;
    @Value("${ncp.s3.endpoint}")
    private String endPoint;
    @Value("${cdn.base-url}")
    private String cdnBaseUrl;

    @Async("imageExecutor")
    @Transactional
    @Override
    public void upsertPollImages(Long id, List<String> addImageUrls, List<String> removeImages, PollType pollType) {
        final List<String> adds = normalizeList(addImageUrls);
        final List<String> removes = normalizeList(removeImages);

        // 변경사항이 없으면 종료
        if (adds.isEmpty() && removes.isEmpty()) return;

        // 1) [DB 삭제] 사용자 제거 요청 처리 + S3 삭제 키 수집
        List<String> bulkDeleteKeys = deleteRemovedImagesAndCollectKeys(id, removes);

        // 2) [생존 조회] DB에 남은 이미지 조회 + Position 재정렬(0부터) + 중복 방지용 URL 집합 생성
        SurvivorContext survivorContext = loadAndReorderSurvivors(id);

        // 추가할 이미지가 없다면 여기서 S3 삭제 처리 후 종료
        if (adds.isEmpty()) {
            if (!bulkDeleteKeys.isEmpty()) {
                storageClient.deleteObjectsBulk(bulkDeleteKeys);
            }
            return;
        }

        final String basePrefix = "vote/" + id;

        // 3) [병렬 Copy] Staging -> Production 복사 (생존 이미지 다음 순서부터 시작)
        CopyResult copyResult = copyNewImagesInParallel(
                id,
                adds,
                basePrefix,
                survivorContext.survivorUrls(),
                survivorContext.nextPosition()
        );

        // 4) [DB 저장] 새로 추가된 이미지 저장
        if (!copyResult.toSave().isEmpty()) {
            List<Image> savedImages = imageRepository.saveAll(copyResult.toSave());
            publishModerationEvents(savedImages);
        }

        // 5) [S3 삭제] 사용자 삭제분 + Staging 원본 일괄 삭제
        bulkDeleteKeys.addAll(copyResult.stagingToDelete());
        if (!bulkDeleteKeys.isEmpty()) {
            storageClient.deleteObjectsBulk(bulkDeleteKeys);
        }
    }


    private void publishModerationEvents(List<Image> savedImages) {
        for (Image img : savedImages) {
            String key = UrlUtil.toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, img.getUrl());
            eventPublisher.publishEvent(new ImageModerationEvent(img.getId(), key));
        }
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
        var stagingToDelete = new ConcurrentLinkedQueue<String>();

        // 1. CompletableFuture를 사용하여 imageExecutor에서 비동기 작업 스트림 생성
        List<CompletableFuture<Image>> futures = new ArrayList<>();

        for (int i = 0; i < adds.size(); i++) {
            final int myOrder = startOrder + i;
            final String raw = adds.get(i);

            var future = CompletableFuture.supplyAsync(() -> {
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

                if (storageClient.isStagingKey(srcKey) && !srcKey.equals(finalKey) && !isDefaultUrlOrKey(srcKey)) {
                    stagingToDelete.add(srcKey);
                }

                return Image.of(ImageType.POST, postId, finalUrl, myOrder, ImageModerationStatus.CLEAN, null);
            }, imageExecutor);

            futures.add(future);
        }

        // 2. 모든 작업이 완료될 때까지 대기 및 결과 수집
        try {
            List<Image> toSave = futures.stream()
                    .map(CompletableFuture::join) // 결과가 나올 때까지 대기
                    .filter(Objects::nonNull)
                    .toList();

            return new CopyResult(toSave, new ArrayList<>(stagingToDelete));
        } catch (Exception e) {
            log.error("[Vote IMG] Parallel copy failed", e);
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        }
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

package core.global.entity.image.service.impl;

import core.global.entity.image.dto.ImageModerationEvent;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.enums.ImageModerationStatus;
import core.global.enums.errorcode.ImageErrorCode;
import core.global.exception.BusinessException;
import core.global.service.ContentModerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageModerationEventListener {

    private final ContentModerationService moderationService;
    private final ImageRepository imageRepository;
    private final S3Client s3Client;

    @Value("${ncp.s3.bucket}")
    private String bucket;

    @Async("moderationExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleModerationEvent(ImageModerationEvent event) {
        log.info("🔍 [Async] 유해성 검사 시작: imageId={}", event.getImageId());

        try {
            ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(b -> b.bucket(bucket).key(event.getS3Key()));
            byte[] data = objectBytes.asByteArray();
            String filename = event.getS3Key().substring(event.getS3Key().lastIndexOf('/') + 1);

            ContentModerationService.ModerationResult result = moderationService.inspectImage(data, filename);

            Image image = imageRepository.findById(event.getImageId())
                    .orElseThrow(() -> new BusinessException(ImageErrorCode.IMAGE_NOT_FOUND));

            if (result.isHarmful()) {
                log.warn("🚨 유해 이미지 적발! ID={}, Reason={}", event.getImageId(), result.getReason());
                image.updateModerationStatus(ImageModerationStatus.SUSPICIOUS, result.getReason());
            } else {
                log.info("✅ 유해성 검사 통과: ID={}", event.getImageId());
                image.updateModerationStatus(ImageModerationStatus.CLEAN, null);
            }

        } catch (Exception e) {
            log.error("❌ 비동기 검사 중 오류 발생: imageId={}", event.getImageId(), e);
        }
    }
}

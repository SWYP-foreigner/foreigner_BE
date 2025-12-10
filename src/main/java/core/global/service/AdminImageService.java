package core.global.service;

import core.domain.chat.entity.ChatMessage;
import core.domain.chat.repository.ChatMessageRepository;
import core.global.entity.image.S3Props;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.enums.ImageModerationStatus;
import core.global.enums.ImageType;
import core.global.enums.errorcode.ImageErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminImageService {

    private final ImageRepository imageRepository;
    private final S3Client s3Client;
    private final S3Props s3Props;
    private final ChatMessageRepository chatMessageRepository;

    @Transactional(readOnly = true)
    public List<Image> getSuspiciousImages() {
        return imageRepository.findByModerationStatusOrderByIdDesc(ImageModerationStatus.SUSPICIOUS);
    }

    @Transactional
    public void approveImage(Long imageId) {
        Image image = imageRepository.findById(imageId)
                .orElseThrow(() -> new BusinessException(ImageErrorCode.IMAGE_NOT_FOUND));

        image.markAsClean();
    }

    @Transactional
    public void deleteImage(Long imageId) {
        Image image = imageRepository.findById(imageId)
                .orElseThrow(() -> new BusinessException(ImageErrorCode.IMAGE_NOT_FOUND));

        deleteFromS3(image.getUrl());

        if (image.getImageType() == ImageType.CHAT_MEDIA) {
            chatMessageRepository.findById(image.getRelatedId())
                    .ifPresent(ChatMessage::maskContentAsDeleted);
        }

        imageRepository.delete(image);
    }

    private void deleteFromS3(String url) {
        String s3Key = extractKeyFromUrl(url);
        if (s3Key != null) {
            try {
                s3Client.deleteObject(b -> b.bucket(s3Props.getBucket()).key(s3Key));
                log.info("S3 Image deleted: {}", s3Key);
            } catch (Exception e) {
                log.error("Failed to delete image from S3: {}", s3Key, e);
            }
        }
    }

    private String extractKeyFromUrl(String url) {
        try {
            URI uri = new URI(url);
            String path = uri.getPath();

            if (path == null || path.isEmpty()) {
                return null;
            }

            if (path.startsWith("/")) {
                path = path.substring(1);
            }

            String bucketName = s3Props.getBucket();
            if (path.startsWith(bucketName + "/")) {
                return path.substring(bucketName.length() + 1);
            }
            return path;

        } catch (URISyntaxException e) {
            log.error("Failed to parse URL: {}", url, e);
            return null;
        }
    }
}

package core.global.service;

import core.global.entity.image.S3Props;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.enums.ImageModerationStatus;
import core.global.enums.errorcode.ImageErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminImageService {

    private final ImageRepository imageRepository;
    private final S3Client s3Client;
    private final S3Props s3Props;

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
        String prefix = s3Props.getEndPoint() + "/" + s3Props.getBucket() + "/";
        if (url.startsWith(prefix)) {
            return url.replace(prefix, "");
        }
        return null;
    }
}

package core.global.service;

import core.domain.chat.entity.ChatMessage;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.post.repository.PostRepository;
import core.domain.user.repository.UserRepository;
import core.global.entity.image.S3Props;
import core.global.entity.image.dto.SuspiciousImageResponse;
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
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminImageService {

    private final ImageRepository imageRepository;
    private final S3Client s3Client;
    private final S3Props s3Props;
    private final ChatMessageRepository chatMessageRepository;
    private final PostRepository postRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<SuspiciousImageResponse> getSuspiciousImages() {
        List<Image> images = imageRepository.findByModerationStatusOrderByIdDesc(ImageModerationStatus.SUSPICIOUS);
        return images.stream().map(this::convertToDto).collect(Collectors.toList());
    }

    private SuspiciousImageResponse convertToDto(Image img) {
        Long uploaderId = null;
        String uploaderName = "알 수 없음";

        try {
            if (img.getImageType() == ImageType.USER) {
                uploaderId = img.getRelatedId();
                uploaderName = userRepository.findById(uploaderId)
                        .map(u -> u.getFirstName() + " " + u.getLastName())
                        .orElse("탈퇴한 유저");

            } else if (img.getImageType() == ImageType.POST) {
                var post = postRepository.findById(img.getRelatedId());
                if (post.isPresent()) {
                    uploaderId = post.get().getAuthor().getId();
                    uploaderName = post.get().getAuthor().getFirstName();
                }

            } else if (img.getImageType() == ImageType.CHAT_MEDIA) {
                var msg = chatMessageRepository.findById(img.getRelatedId());
                if (msg.isPresent()) {
                    uploaderId = msg.get().getSender().getId();
                    uploaderName = msg.get().getSender().getFirstName();
                }
            }
        } catch (Exception e) {
            log.warn("이미지(ID:{})의 업로더 정보를 찾을 수 없습니다.", img.getId());
        }

        return SuspiciousImageResponse.from(img, uploaderId, uploaderName);
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

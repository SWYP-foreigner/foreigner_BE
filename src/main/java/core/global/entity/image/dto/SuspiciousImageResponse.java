package core.global.entity.image.dto;

import core.global.entity.image.entity.Image;
import core.global.enums.ImageType;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SuspiciousImageResponse {
    private Long id;
    private String url;
    private String moderationReason;
    private ImageType imageType;
    private Long relatedId;
    private Long uploaderId;
    private String uploaderName;

    public static SuspiciousImageResponse from(Image image, Long uploaderId, String uploaderName) {
        return SuspiciousImageResponse.builder()
                .id(image.getId())
                .url(image.getUrl())
                .moderationReason(image.getModerationReason())
                .imageType(image.getImageType())
                .relatedId(image.getRelatedId())
                .uploaderId(uploaderId)
                .uploaderName(uploaderName)
                .build();
    }
}

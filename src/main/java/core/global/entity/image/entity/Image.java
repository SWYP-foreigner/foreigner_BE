package core.global.entity.image.entity;

import core.global.enums.ImageModerationStatus;
import core.global.enums.ImageType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "image",
        indexes = {
                @Index(name = "idx_image_type_related_id", columnList = "image_type, related_id, image_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Image {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "image_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "image_type", nullable = false)
    private ImageType imageType;

    @Column(name = "related_id", nullable = false)
    private Long relatedId;

    @Column(name = "url", length = 150, nullable = false)
    private String url;

    @Column(name = "order_index", nullable = false)
    private Integer orderIndex;

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", nullable = false)
    private ImageModerationStatus moderationStatus = ImageModerationStatus.CLEAN;

    @Column(name = "moderation_reason")
    private String moderationReason;

    @Builder
    private Image(ImageType imageType, Long relatedId, String url, Integer orderIndex,
                  ImageModerationStatus moderationStatus, String moderationReason) {
        this.imageType = imageType;
        this.relatedId = relatedId;
        this.url = url;
        this.orderIndex = orderIndex;
        this.moderationStatus = (moderationStatus != null) ? moderationStatus : ImageModerationStatus.CLEAN;
        this.moderationReason = moderationReason;
    }

    public static Image of(ImageType type, Long relatedId, String url, int orderIndex) {
        return Image.builder()
                .imageType(type)
                .relatedId(relatedId)
                .url(url)
                .orderIndex(orderIndex)
                .build();
    }

    public static Image of(ImageType type, Long relatedId, String url, int orderIndex,
                           ImageModerationStatus status, String reason) {
        return Image.builder()
                .imageType(type)
                .relatedId(relatedId)
                .url(url)
                .orderIndex(orderIndex)
                .moderationStatus(status)
                .moderationReason(reason)
                .build();
    }

    public void markAsClean() {
        this.moderationStatus = ImageModerationStatus.CLEAN;
        this.moderationReason = null;
    }

    public void changePosition(int position) {
        this.orderIndex = position;
    }

    public void updateModerationStatus(ImageModerationStatus status, String reason) {
        this.moderationStatus = status;
        this.moderationReason = reason;
    }
}

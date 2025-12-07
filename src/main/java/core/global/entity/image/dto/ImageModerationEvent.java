package core.global.entity.image.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ImageModerationEvent {
    private final Long imageId;
    private final String s3Key;
}

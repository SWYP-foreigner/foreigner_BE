package core.global.image.dto;

import core.global.enums.ImageType;

import java.util.List;

public record PresignedUrlRequest(
        ImageType imageType,
        String uploadSessionId,
        List<FileSpec> files
) {
    public record FileSpec(
            String filename,                // 예: "placeA_1.jpg"
            String contentType              // 예: "image/jpeg"
    ) {}
}

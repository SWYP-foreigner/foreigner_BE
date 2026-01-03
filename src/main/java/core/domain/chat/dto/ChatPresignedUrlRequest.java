package core.domain.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ChatPresignedUrlRequest(
        @Schema(description = "업로드할 파일명 (확장자 포함)", example = "image.png") String fileName) {}

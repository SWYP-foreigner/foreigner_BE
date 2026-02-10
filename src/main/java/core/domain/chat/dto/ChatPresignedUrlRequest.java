package core.domain.chat.dto;

import core.global.enums.chat.MessageType;
import io.swagger.v3.oas.annotations.media.Schema;

public record ChatPresignedUrlRequest(
        @Schema(description = "업로드할 파일명 (확장자 포함)", example = "myvideo.mp4")
        String fileName,

        @Schema(description = "파일 타입 (IMAGE 또는 VIDEO)", example = "VIDEO")
        MessageType fileType
) {}
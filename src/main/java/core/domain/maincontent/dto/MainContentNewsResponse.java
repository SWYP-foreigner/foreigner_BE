package core.domain.maincontent.dto;

import core.global.enums.KNewsContentType;
import io.swagger.v3.oas.annotations.media.Schema;

public record MainContentNewsResponse(
        @Schema(description = "메인콘텐츠 ID", example = "123")
        Long contentId,

        @Schema(description = "메인콘텐츠 제목", example = "ILLIT ~~ BTS ~~")
        String title,

        @Schema(description = "메인콘텐츠 타입", example = "K-POP")
        KNewsContentType type,

        @Schema(description = "메인콘텐츠 올린 뒤 일수", example = "3")
        Long ago,

        @Schema(description = "콘텐츠 이미지 URL(없으면 null)", nullable = true, example = "https://cdn.example.com/p/123.jpg")
        String thumbImageUrl
) {
}

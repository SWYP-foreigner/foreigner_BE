package core.domain.maincontent.dto;

import core.domain.maincontent.entity.KNewsContentType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record MainContentNewsListResponse(
        @Schema(description = "메인콘텐츠 ID", example = "123")
        Long contentId,

        @Schema(description = "메인콘텐츠 제목", example = "ILLIT ~~ BTS ~~")
        String title,

        @Schema(description = "메인콘텐츠 타입", example = "K-POP")
        KNewsContentType type,

        @Schema(description = "메인콘텐츠 올린 뒤 일수", example = "3")
        Long ago,

        @Schema(description = "작성 시간(UTC)", type = "string", format = "date-time", example = "2025-08-20T12:34:56Z")
        Instant createdAt,

        @Schema(description = "콘텐츠 이미지 URL(없으면 null)", nullable = true, example = "https://cdn.example.com/p/123.jpg")
        String thumbImageUrl,

        @Schema(description = "인기 점수(인기 정렬 시 커서용, 없으면 null)", nullable = true, example = "987654321")
        Long score

) {
    public MainContentNewsListResponse {
        if (createdAt != null && ago == null) {
            ago = java.time.Duration.between(createdAt, Instant.now()).toDays();
        }
    }
}

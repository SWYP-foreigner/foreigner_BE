package core.domain.maincontent.controller;

import core.global.enums.KNewsContentType;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "K-Culture 카테고리 목록 응답")
public record KNewsCategoryListResponse(
        @Schema(description = "카테고리", example = "K-POP")
        KNewsContentType category
) {
}

package core.domain.maincontent.controller;

import core.global.enums.KNewsContentType;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "카테고리 목록 응답")
public record KNewsCategoryListResponse(
        @Schema(description = "카테고리 코드", example = "K-POP")
        String code,

        @Schema(description = "화면 표시 이름", example = "🎧K-POP")
        String name
) {
    // ✨ 핵심: Enum 하나만 받는 생성자를 추가하여 this(...)로 주 생성자 호출
    public KNewsCategoryListResponse(KNewsContentType type) {
        this(type.getValue(), type.getLabel());
    }
}
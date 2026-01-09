package core.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
public class ProfileOptionsDto {

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "프로필 옵션 통합 응답 (자기소개 + 관심사)")
    public static class CombinedResponse {

        @Schema(description = "추천 자기소개 문구 리스트 (영어)",
                example = "[\"I want to make Korean friends! 👋\", \"I really love K-POP 🎵\"]")
        private List<String> introductions;

        @Schema(description = "관심사 카테고리 정보")
        private InterestResponse interests;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "관심사 카테고리 래퍼")
    public static class InterestResponse {

        @Schema(description = "카테고리 목록")
        private List<CategoryItem> categories;
    }

    @Getter
    @AllArgsConstructor
    @Schema(description = "개별 관심사 카테고리 아이템")
    public static class CategoryItem {

        @Schema(description = "카테고리 명", example = "K-POP")
        private String category;

        @Schema(description = "해당 카테고리의 키워드 리스트 (이모지 포함)",
                example = "[\"BTS 💜\", \"NewJeans 🐰\", \"SEVENTEEN 💎\"]")
        private List<String> items;
    }
}

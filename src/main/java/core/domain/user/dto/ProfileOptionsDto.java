package core.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

public class ProfileOptionsDto {

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    @Schema(description = "자기소개 추천 문구 목록 응답")
    public static class IntroductionResponse {
        private List<String> recommendations;
    }

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    @Schema(description = "관심사 카테고리별 목록 응답")
    public static class InterestResponse {
        private List<CategoryItem> categories;
    }

    @Getter
    @AllArgsConstructor
    public static class CategoryItem {
        @Schema(description = "카테고리 명 (K-POP, K-DRAMA&MOVIE, LIFESTYLE)")
        private String category;

        @Schema(description = "해당 카테고리의 하위 아이템 목록")
        private List<String> items;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CombinedResponse {
        @Schema(description = "추천 자기소개 문구 (영어)")
        private List<String> introductions;

        @Schema(description = "관심사 카테고리 목록")
        private InterestResponse interests; // 기존 InterestResponse 재사용
    }
}
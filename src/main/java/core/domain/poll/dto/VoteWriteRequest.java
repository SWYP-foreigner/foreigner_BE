package core.domain.poll.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(name = "VoteWriteRequest", description = "일반 투표 생성 요청")
public record VoteWriteRequest(
        @Schema(description = "투표 제목 (질문)", example = "오늘 점심 메뉴 추천해주세요!", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String title,

        @Schema(description = "투표 하단 부연 설명", example = "중식과 일식 중에서 고민 중입니다.", nullable = true)
        String description,

        @Schema(description = "투표 상세 본문 (게시글 본문 내용)", example = "다들 드시고 싶은 메뉴에 투표 부탁드려요.")
        String content,

        @Schema(description = "익명 투표 여부 (true일 경우 투표자 정보 비공개)", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        Boolean isAnonymous,

        @Schema(
                description = "투표 선택지 목록 (최소 2개 이상)",
                example = "[\"짜장면\", \"초밥\", \"돈카츠\"]",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotEmpty
        @Size(min = 2, max = 10)
        List<String> options
) {
}
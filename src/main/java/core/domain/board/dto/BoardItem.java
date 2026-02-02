package core.domain.board.dto;

import core.global.enums.BoardCategory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Schema(name = "BoardResponse", description = "게시글 카드 응답 데이터")
public record BoardItem(
        @Schema(description = "게시글 / 투표 / 퀴즈 ID", example = "123", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,

        @Schema(description = "내용 미리보기", example = "안녕하세요! 첫 글입니다.", requiredMode = Schema.RequiredMode.REQUIRED)
        String contentPreview,

        @Schema(description = "작성자 ID", example = "123", requiredMode = Schema.RequiredMode.REQUIRED)
        Long authorId,

        @Schema(description = "작성자 이름 (익명일 경우 null)", nullable = true, example = "alice")
        String authorName,

        @Schema(description = "게시판 카테고리 (FREE, QNA 등)", requiredMode = Schema.RequiredMode.REQUIRED)
        BoardCategory boardCategory,

        @Schema(description = "작성 시간(UTC)", type = "string", format = "date-time", example = "2025-08-20T12:34:56Z", requiredMode = Schema.RequiredMode.REQUIRED)
        Instant createdAt,

        @Schema(description = "익명 여부", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        Boolean isAnonymous,

        @Schema(description = "좋아요 여부", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
        Boolean isLiked,

        @Schema(description = "북마크 여부", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        Boolean isBookmarked,

        @Schema(description = "좋아요 수", example = "10", requiredMode = Schema.RequiredMode.REQUIRED)
        Long likeCount,

        @Schema(description = "댓글 수", example = "5", requiredMode = Schema.RequiredMode.REQUIRED)
        Long commentCount,

        @Schema(description = "조회 수", example = "1234", requiredMode = Schema.RequiredMode.REQUIRED)
        Long viewCount,

        @Schema(description = "작성자 프로필 이미지 URL (익명 시 null)", nullable = true, example = "https://cdn.example.com/u/alice.png")
        String userImageUrl,

        @Schema(description = "인기 점수 (인기 정렬 시 커서값으로 활용)", nullable = true, example = "987654321")
        Long score,

        @Schema(description = "임시 컬럼 대표 이미지 URL", nullable = true, example = "https://cdn.example.com/p/123.jpg")
        String contentImageUrl,
        @Schema(description = "임시 컬럼 첨부된 이미지 총 개수", nullable = true, example = "2")
        Integer imageCount,

        @Schema(description = "커뮤니티 게시글 상세 정보 (일반 게시글일 경우)")
        PostInfo postInfo,

        @Schema(description = "투표/퀴즈 상세 정보 (투표 게시글일 경우)")
        PollInfo pollInfo
) {
    @Schema(description = "게시글 콘텐츠 정보")
    public record PostInfo(
            @Schema(description = "대표 이미지 URL", nullable = true, example = "https://cdn.example.com/p/123.jpg")
            String contentImageUrl,
            @Schema(description = "첨부된 이미지 총 개수", nullable = true, example = "2")
            Integer imageCount
    ) {
        public PostInfo() {
            this(null, null);
        }
    }

    @Schema(description = "투표 상세 정보")
    public record PollInfo(
            @Schema(description = "투표 제목", example = "가장 선호하는 언어는?")
            String title,
            @Schema(description = "투표 설명", example = "가장 선호하는 언어는 무엇인가요?")
            String description,
            @Schema(description = "투표 마감 시간", example = "2025-09-20T12:00:00Z")
            Instant closeAt,
            @Schema(description = "총 투표 수", example = "150")
            long totalVoteCount,
            @Schema(description = "투표 선택지 목록")
            List<OptionItem> options,
            @Schema(description = "로그인 사용자가 선택한 선택지 ID (미참여 시 null)", example = "1")
            Long selectedOptionId,
            @Schema(description = "정답 정보 옵션 ID", example = "1")
            Long correctOptionId
    ) {
        public PollInfo() {
            this(null, null, null, 0L, new ArrayList<>(), null, null);
        }
    }

    @Schema(description = "투표 선택지 정보")
    public record OptionItem(
            @Schema(description = "선택지 ID", example = "1")
            Long optionId,
            @Schema(description = "선택지 내용", example = "Java")
            String content,
            @Schema(description = "해당 항목 투표 수", example = "45")
            long voteCount
    ) {
        public OptionItem() {
            this(null, null, 0L);
        }
    }
}
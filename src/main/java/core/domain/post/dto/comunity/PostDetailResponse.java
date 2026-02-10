package core.domain.post.dto.comunity;

import core.global.enums.community.BoardCategory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Schema(description = "게시글 상세 응답")
public record PostDetailResponse(
        @Schema(description = "ID", example = "1")
        Long id,

        @Schema(description = "본문", example = "Hello~ I came to Korea from the U.S. as an exchange student")
        String content,

        @Schema(description = "작성자 ID", example = "1")
        Long authorId,

        @Schema(description = "작성자 표시명(익명이면 'Anonymity')", example = "Anonymity")
        String authorName,

        @Schema(description = "카테고리", example = "NEWS")
        BoardCategory boardCategory,

        @Schema(description = "작성 시각 (UTC)", type = "string", format = "date-time", example = "2025-08-13T09:41:00Z")
        Instant createdTime,

        @Schema(description = "채팅방 링크", example = "https://ko-ri.cloud/chatroom/10")
        String link,

        @Schema(description = "익명 여부", example = "true")
        Boolean isAnonymous,

        @Schema(description = "좋아요 여부", example = "true")
        Boolean isLiked,

        @Schema(description = "북마크 여부", example = "true")
        Boolean isBookmarked,

        @Schema(description = "좋아요 수", example = "12")
        Long likeCount,

        @Schema(description = "댓글 수", example = "3")
        Long commentCount,

        @Schema(description = "조회 수", example = "257")
        Long viewCount,

        @Schema(description = "작성자 프로필 이미지 URL", example = "https://cdn.example.com/u/123/avatar.png")
        String userImageUrl,

        @Schema(description = "임시 컬럼  본문 내 이미지 URL 목록",
                example = "[\"https://cdn.example.com/p/1.png\",\"https://cdn.example.com/p/2.jpg\"]")
        List<String> contentImageUrls,

        @Schema(description = "임시 컬럼  이미지 수", example = "3")
        Integer imageCount,

        @Schema(description = "커뮤니티 게시글 상세 정보 (일반 게시글일 경우)")
        PostInfo postInfo,

        @Schema(description = "투표/퀴즈 상세 정보 (투표 게시글일 경우)")
        PollInfo pollInfo
) {

    public PostDetailResponse(PostDetailResponse postDetail, String translatedContent) {
        this(
                postDetail.id(),
                translatedContent,
                postDetail.authorId(),
                postDetail.authorName(),
                postDetail.boardCategory(),
                postDetail.createdTime(),
                postDetail.link(),
                postDetail.isAnonymous(),
                postDetail.isLiked(),
                postDetail.isBookmarked(),
                postDetail.likeCount(),
                postDetail.commentCount(),
                postDetail.viewCount(),
                postDetail.userImageUrl(),
                postDetail.contentImageUrls(),
                postDetail.imageCount(),
                new PostInfo(postDetail.contentImageUrls(), postDetail.imageCount()),
                (postDetail.boardCategory() == BoardCategory.QUIZ || postDetail.boardCategory() == BoardCategory.VOTE)
                        ? postDetail.pollInfo()
                        : null
        );
    }

    @Schema(description = "게시글 콘텐츠 정보")
    public record PostInfo(
            @Schema(description = "대표 이미지 URL", nullable = true, example = "https://cdn.example.com/p/123.jpg")
            List<String> contentImageUrl,
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
            List<PostDetailResponse.OptionItem> options,
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

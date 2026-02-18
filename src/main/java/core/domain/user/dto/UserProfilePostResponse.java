package core.domain.user.dto;

import lombok.Builder;
import lombok.Getter;
import java.time.Instant;

@Getter
@Builder
@io.swagger.v3.oas.annotations.media.Schema(description = "유저 프로필용 게시글 응답 DTO")
public class UserProfilePostResponse {

    @io.swagger.v3.oas.annotations.media.Schema(description = "게시글 고유 ID", example = "102")
    private Long postId;

    @io.swagger.v3.oas.annotations.media.Schema(description = "게시글 본문 내용", example = "오늘 날씨가 너무 좋네요!")
    private String content;

    @io.swagger.v3.oas.annotations.media.Schema(description = "대표 이미지 URL (없으면 null)", example = "https://cdn.example.com/thumb.jpg")
    private String thumbnailUrl;

    @io.swagger.v3.oas.annotations.media.Schema(description = "좋아요 총 개수", example = "15")
    private long likeCount;

    @io.swagger.v3.oas.annotations.media.Schema(description = "댓글 총 개수", example = "3")
    private long commentCount;

    @io.swagger.v3.oas.annotations.media.Schema(description = "작성일시")
    private Instant createdAt;
}
package core.domain.user.dto;

import lombok.Builder;
import lombok.Getter;
import java.time.Instant;
import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Builder
@Schema(description = "유저 프로필용 게시글 응답 DTO")
public class UserProfilePostResponse {

    @Schema(description = "게시글 ID", example = "102")
    private Long id; // postId -> id

    @Schema(description = "내용 미리보기", example = "오늘 날씨가 너무 좋네요!.  프론트에서 일정 글자수 넘어가면 커뮤니티와 동일하게 짤라주는걸로 가정")
    private String contentPreview; // content -> contentPreview

    @Schema(description = "대표 이미지 URL (없으면 null)", example = "https://cdn.example.com/thumb.jpg")
    private String contentImageUrl; // thumbnailUrl -> contentImageUrl

    @Schema(description = "좋아요 수", example = "15")
    private Long likeCount; // long -> Long (타입 일관성)

    @Schema(description = "댓글 수", example = "3")
    private Long commentCount; // long -> Long (타입 일관성)

    @Schema(description = "작성 시간(UTC)")
    private Instant createdAt;
}
package core.domain.user.dto;

import lombok.Builder;
import lombok.Getter;
import java.time.Instant;

@Getter
@Builder
public class UserProfilePostResponse {
    private Long postId;
    private String content;        // 게시글 내용 (필요 시 앞부분만 잘라서 줄 수도 있음)
    private String thumbnailUrl;   // 대표 이미지 (Index 0)
    private long likeCount;        // 좋아요 수
    private long commentCount;     // 댓글 수
    private Instant createdAt;     // 작성일
}
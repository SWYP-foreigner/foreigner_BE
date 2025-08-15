package core.domain.post.dto;

import java.time.Instant;
import java.util.List;

public record PostDetailIntermediate(
        String title,
        String content,
        String userName,
        Instant createdTime,
        Long likeCount,
        Long commentCount,
        Long viewCount,
        String userImageUrl,
        List<String> contentImageUrls
) {}

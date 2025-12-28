package core.domain.mainpage.dto;

import java.time.Instant;
import java.util.List;

public record PollDetailResponse(
        String question,
        List<String> answers,
        boolean isLiked,
        int likeCount,
        int voteCount,
        Instant closeTime,
        int selectedAnswer
) {
}

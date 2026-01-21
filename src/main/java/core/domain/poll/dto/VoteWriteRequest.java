package core.domain.poll.dto;

import java.util.List;

public record VoteWriteRequest(
        String content,
        String title,
        String description,
        Boolean isAnonymous,
        List<String> options
) {
}

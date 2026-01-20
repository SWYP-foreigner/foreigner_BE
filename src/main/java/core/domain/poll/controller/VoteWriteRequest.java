package core.domain.poll.controller;

import java.util.List;

public record VoteWriteRequest(
        String content,
        String title,
        String description,
        Boolean isAnonymous,
        List<String> options
) {
}

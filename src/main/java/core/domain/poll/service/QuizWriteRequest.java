package core.domain.poll.service;

import java.util.List;

public record QuizWriteRequest(
        String content,
        String title,
        String description,
        List<String> options
) {
}

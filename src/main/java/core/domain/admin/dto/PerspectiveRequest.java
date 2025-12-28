package core.domain.admin.dto;

import java.util.Map;

public record PerspectiveRequest(
        Comment comment,
        Map<String, ScoreThreshold> requestedAttributes,
        boolean doNotStore,
        String clientToken
) {
    public record Comment(String text) {}
    public record ScoreThreshold() {} // 빈 객체 {} 전송용

    public static PerspectiveRequest forCheck(String text, Map<String, ScoreThreshold> attributesToRequest) {
        return new PerspectiveRequest(
                new Comment(text),
                attributesToRequest,
                true,
                null
        );
    }
}

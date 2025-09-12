package core.global.search.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import core.domain.post.entity.Post;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostDocument(
        Long boardId,
        Long userId,
        Long createdAt,
        Long checkCount,
        String content,
        Object contentSuggest,        // 동의어/오타 허용 (english_custom)
        Object contentSuggestExact    // 정확 접두 (english_completion_exact)
) {
    // 가중치 파라미터(필요시 @Value 등으로 주입 가능)
    private static final double ALPHA = 0.65;     // 인기 비중
    private static final double HALF_LIFE_D = 30; // 신선도 반감기(일)
    private static final double POP_NORM = 1000;  // log 정규화 기준(트래픽에 맞춰 조정)

    public PostDocument(Post p) {
        this(
                p.getBoard() != null ? p.getBoard().getId() : null,
                p.getAuthor() != null ? p.getAuthor().getId() : null,
                p.getCreatedAt() == null ? null : p.getCreatedAt().toEpochMilli(),
                p.getCheckCount(),
                p.getContent(),
                toCompletionValue(p.getContent(),
                        weight(p.getCheckCount(), p.getCreatedAt() == null ? null : p.getCreatedAt().toEpochMilli()),
                        p.getBoard() != null ? p.getBoard().getId() : null),
                toCompletionValue(p.getContent(),
                        weight(p.getCheckCount(), p.getCreatedAt() == null ? null : p.getCreatedAt().toEpochMilli()),
                        p.getBoard() != null ? p.getBoard().getId() : null)
        );
    }

    private static int weight(Long checkCount, Long createdAtMillis) {
        long now = System.currentTimeMillis();
        long created = (createdAtMillis == null ? now : createdAtMillis);
        long cc = (checkCount == null ? 0L : checkCount);

        double ageDays = (now - created) / 86_400_000.0;
        double fresh = Math.exp(-ageDays / HALF_LIFE_D);           // 0~1
        double pop   = Math.log1p(cc) / Math.log1p(POP_NORM);      // 0~1
        if (pop > 1.0) pop = 1.0;

        double score = ALPHA * pop + (1 - ALPHA) * fresh;          // 0~1
        int w = (int)Math.round(100.0 * Math.max(0.0, Math.min(1.0, score)));
        return w;
    }

    private static Object toCompletionValue(String text, int weight, Long boardId) {
        if (text == null || text.isBlank()) return null;
        String normalized = text.toLowerCase(Locale.ROOT);

        if (boardId == null) {
            return Map.of(
                    "input", List.of(normalized),
                    "weight", Math.max(0, weight)
            );
        }
        return Map.of(
                "input", List.of(normalized),
                "weight", Math.max(0, weight),
                "contexts", Map.of("boardId", List.of(String.valueOf(boardId)))
        );
    }
}
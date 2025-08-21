package core.domain.board.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

@Schema(name = "BoardCursorPageResponse", description = "게시판 커서 기반 페이지 공통 응답")
public record BoardCursorPageResponse<T>(
        @Schema(description = "항목 리스트(제네릭)", requiredMode = Schema.RequiredMode.REQUIRED)
        List<T> items,

        @Schema(description = "다음 페이지 존재 여부", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean hasNext,

        @Schema(description = "다음 커서 createdAt(최신 정렬에서 사용, 없으면 null)",
                type = "string", format = "date-time", example = "2025-08-20T12:33:00Z", nullable = true)
        Instant nextCursorCreatedAt,

        @Schema(description = "다음 커서 ID(없으면 null)", example = "120", nullable = true)
        Long nextCursorId,

        @Schema(description = "다음 커서 점수(인기 정렬에서 사용, 없으면 null)", example = "123456", nullable = true)
        Long nextCursorScore
) {
    // LATEST용
    public static <T> BoardCursorPageResponse<T> ofLatest(List<T> rows, int size,
                                                          Function<T, Instant> createdAtGetter,
                                                          Function<T, Long> idGetter) {
        boolean hasNext = rows.size() > size;
        List<T> items = hasNext ? rows.subList(0, size) : rows;

        Instant nextCreatedAt = null;
        Long nextId = null;
        if (!items.isEmpty()) {
            T last = items.getLast();
            nextCreatedAt = createdAtGetter.apply(last);
            nextId = idGetter.apply(last);
        }
        return new BoardCursorPageResponse<>(items, hasNext, nextCreatedAt, nextId, null);
    }

    // POPULAR용
    public static <T> BoardCursorPageResponse<T> ofPopular(List<T> rows, int size,
                                                           Function<T, Long> scoreGetter,
                                                           Function<T, Long> idGetter) {
        boolean hasNext = rows.size() > size;
        List<T> items = hasNext ? rows.subList(0, size) : rows;

        Long nextScore = null;
        Long nextId = null;
        if (!items.isEmpty()) {
            T last = items.getLast();
            nextScore = scoreGetter.apply(last);
            nextId = idGetter.apply(last);
        }
        return new BoardCursorPageResponse<>(items, hasNext, null, nextId, nextScore);
    }
}

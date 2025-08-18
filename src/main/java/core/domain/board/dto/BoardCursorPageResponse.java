package core.domain.board.dto;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

public record BoardCursorPageResponse<T>(
        List<T> items,
        boolean hasNext,
        Instant nextCursorCreatedAt,
        Long nextCursorId,
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

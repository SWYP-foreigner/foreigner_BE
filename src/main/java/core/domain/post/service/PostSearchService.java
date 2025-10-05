package core.domain.post.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import core.domain.board.repository.BoardRepository;
import core.domain.post.repository.PostSearchRepositoryCustom;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.UserRepository;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import core.global.pagination.CursorPageResponse;
import core.global.search.dto.SearchResultView;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PostSearchService {

    private final PostSearchRepositoryCustom searchRepository;
    private final BoardRepository boardRepository;
    private final BlockRepository blockRepository;
    private final UserRepository userRepository;

    private static final int LIMIT = 5;

    @Transactional(readOnly = true)
    public CursorPageResponse<SearchResultView> search(
            String q,
            Long boardId,
            String cursor,
            int size
    ) {
        final int pageSize = Math.min(Math.max(size, 1), 50);
        final Long resolvedBoardId = (boardId != null && boardId == 1L) ? null : boardId;

        if (resolvedBoardId != null && !boardRepository.existsById(resolvedBoardId)) {
            throw new BusinessException(ErrorCode.BOARD_NOT_FOUND);
        }

        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        List<Long> blockedIds = blockRepository.getBlockUsersByUserEmail(email)
                .stream().map(User::getId).toList();

        Map<String, Object> c = safeDecode(cursor);
        Instant afterTime = (c.get("t") instanceof String s) ? Instant.parse(s) : (Instant) c.get("t");
        Long afterId = (c.get("id") == null) ? null : ((Number) c.get("id")).longValue();

        List<SearchResultView> rowsPlusOne =
                searchRepository.search(q, resolvedBoardId, blockedIds, afterTime, afterId, pageSize + 1);

        boolean hasNext = rowsPlusOne.size() > pageSize;
        List<SearchResultView> items = hasNext ? rowsPlusOne.subList(0, pageSize) : rowsPlusOne;

        String nextCursor = null;
        if (hasNext) {
            var last = items.get(items.size() - 1);
            nextCursor = safeEncode(Map.of(
                    "t", last.item().createdAt(),
                    "id", last.item().postId()
            ));
        }

        return new CursorPageResponse<>(items, hasNext, nextCursor);
    }


    @Transactional(readOnly = true, timeout = 1)
    public List<String> suggest(String prefix, Long boardId) {
        String pfx = prefix == null ? "" : prefix.trim();
        if (pfx.isEmpty()) return List.of();

        // boardId == 1L → 전체
        Long resolvedBoardId = (boardId != null && boardId == 1L) ? null : boardId;

        // 차단 유저 목록
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        List<Long> blockedIds = blockRepository.getBlockUsersByUserEmail(email)
                .stream().map(User::getId).toList();

        // 리포지토리 위임
        return searchRepository.suggest(pfx, resolvedBoardId, blockedIds, LIMIT);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeDecode(String cursor) {
        if (cursor == null || cursor.isBlank()) return Map.of();
        try {
            String json = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            return new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of(); // 깨진 커서는 첫 페이지로 취급
        }
    }

    private String safeEncode(Map<String, Object> m) {
        try {
            String json = new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .writeValueAsString(m);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static Long toLong(Object o) { return (o == null) ? null : ((Number) o).longValue(); }
    private static Double toDouble(Object o) { return (o == null) ? null : ((Number) o).doubleValue(); }
}

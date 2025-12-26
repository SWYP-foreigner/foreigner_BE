package core.domain.post.service.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import core.domain.board.repository.BoardRepository;
import core.domain.post.dto.search.SearchResultView;
import core.domain.post.repository.PostSearchRepositoryCustom;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.UserRepository;
import core.global.enums.errorcode.CommunityErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import core.global.pagination.CursorPageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostSearchService {

    private static final int LIMIT = 7;
    private static final int FAST_FIRST_MAX = 4;   // 메모리 최대
    private static final int DB_FALLBACK_MAX = 3;  // DB 최대
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    private final PostSearchRepositoryCustom searchRepository;
    private final BoardRepository boardRepository;
    private final BlockRepository blockRepository;
    private final UserRepository userRepository;
    private final SuggestMemoryIndex memoryIndex;

    @Transactional(readOnly = true)
    public CursorPageResponse<SearchResultView> search(
            String q,
            Long boardId,
            String cursor,
            int size
    ) {
        final int pageSize = Math.min(Math.max(size, 1), 20);
        final Long resolvedBoardId = (boardId != null && boardId == 1L) ? null : boardId;

        if (resolvedBoardId != null && !boardRepository.existsById(resolvedBoardId)) {
            throw new BusinessException(CommunityErrorCode.BOARD_NOT_FOUND);
        }

        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email).orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        List<Long> blockedIds = blockRepository.getBlockUsersByUserEmail(email)
                .stream().map(User::getId).toList();

        Map<String, Object> c = safeDecode(cursor);

        Instant afterTime = parseInstant(c.get("t"));
        Long afterId = (c.get("id") instanceof Number n) ? n.longValue() : null;
        Double afterScore = (c.get("sc") instanceof Number n) ? n.doubleValue() : null; // score 추가

        List<SearchResultView> rowsPlusOne = searchRepository.search(
                q, user.getId(), resolvedBoardId, blockedIds,
                afterScore, afterTime, afterId, pageSize + 1
        );

        boolean hasNext = rowsPlusOne.size() > pageSize;
        List<SearchResultView> items = hasNext ? rowsPlusOne.subList(0, pageSize) : rowsPlusOne;

        String nextCursor = null;
        if (hasNext) {
            var last = items.get(items.size() - 1);
            nextCursor = safeEncode(Map.of(
                    "sc", last.score(),
                    "t", last.item().createdAt(),
                    "id", last.item().postId()
            ));
        }

        return new CursorPageResponse<>(items, hasNext, nextCursor);
    }

    private Instant parseInstant(Object obj) {
        if (obj instanceof String s) return Instant.parse(s);
        if (obj instanceof Number n) {
            long sec = n.longValue();
            int nano = (int) ((n.doubleValue() - sec) * 1_000_000_000);
            return Instant.ofEpochSecond(sec, nano);
        }
        return null;
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

        // 1) 메모리 자동완성 우선 (최대 FAST_FIRST_MAX, 단 총 LIMIT 고려)
        int fastQuota = Math.min(FAST_FIRST_MAX, LIMIT);
        List<String> fast = memoryIndex.suggestPrefix(pfx, fastQuota);

        // 2) 부족분만 PGroonga로 보충 (최대 DB_FALLBACK_MAX, 단 총 LIMIT 고려)
        int remain = Math.max(0, LIMIT - fast.size());
        int dbQuota = Math.min(DB_FALLBACK_MAX, remain);

        List<String> db = List.of();
        if (dbQuota > 0) {
            db = searchRepository.suggest(pfx, resolvedBoardId, blockedIds, dbQuota);
        }

        // 3) 머지: 메모리 우선 순서 보존 + 중복 제거 + 총 LIMIT 절단
        LinkedHashSet<String> merged = new LinkedHashSet<>(fast);
        for (String s : db) {
            if (merged.size() >= LIMIT) break;
            merged.add(s);
        }

        return new ArrayList<>(merged);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeDecode(String cursor) {
        if (cursor == null || cursor.isBlank()) return Map.of();
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            // 매번 new 하지 않고 static mapper 사용
            return mapper.readValue(decoded, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String safeEncode(Map<String, Object> m) {
        if (m == null || m.isEmpty()) return null;
        try {
            // 1. 이미 생성된 MAPPER 재사용
            byte[] jsonBytes = mapper.writeValueAsBytes(m);
            // 2. 바이트 배열로 바로 인코딩 (속도 향상)
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(jsonBytes);
        } catch (Exception e) {
            log.error("Cursor encoding error", e);
            return null;
        }
    }
}

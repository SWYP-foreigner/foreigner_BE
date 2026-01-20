package core.domain.post.service.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import core.domain.board.dto.BoardItem;
import core.domain.board.repository.BoardRepository;
import core.domain.bookmark.repository.BookmarkRepository;
import core.domain.comment.repository.CommentRepository;
import core.domain.post.dto.search.PostSearchProjection;
import core.domain.post.dto.search.PostSearchRequest;
import core.domain.post.dto.search.SearchResultView;
import core.domain.post.repository.PostSearchRepositoryCustom;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.UserRepository;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.like.repository.LikeRepository;
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
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostSearchService {

    private static final int LIMIT = 9;
    private static final int FAST_FIRST_MAX = 9;   // 메모리 최대
    private static final int DB_FALLBACK_MAX = 3;  // DB 최대
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    private final PostSearchRepositoryCustom searchRepository;
    private final BoardRepository boardRepository;
    private final BlockRepository blockRepository;
    private final CommentRepository commentRepository;
    private final LikeRepository likeRepository;
    private final ImageRepository imageRepository;
    private final UserRepository userRepository;
    private final BookmarkRepository bookmarkRepository;
    private final PostSuggestIndex memoryIndex;

    @Transactional(readOnly = true)
    public CursorPageResponse<SearchResultView> search(
            String q,
            Long boardId,
            String cursor,
            int size
    ) {
        // 1. 사전 준비 및 유저/차단 정보 조회
        final int pageSize = Math.min(Math.max(size, 1), 20);
        final Long resolvedBoardId = (boardId != null && boardId == 1L) ? null : boardId;

        if (resolvedBoardId != null && !boardRepository.existsById(resolvedBoardId)) {
            throw new BusinessException(CommunityErrorCode.BOARD_NOT_FOUND);
        }

        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email).orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        List<Long> blockedIds = blockRepository.getBlockUsersByUserEmail(email)
                .stream().map(User::getId).toList();

        // 2. 커서 디코딩
        Map<String, Object> c = safeDecode(cursor);
        Instant afterTime = parseInstant(c.get("t"));
        Long afterId = (c.get("id") instanceof Number n) ? n.longValue() : null;
        Double afterScore = (c.get("sc") instanceof Number n) ? n.doubleValue() : null; // score 추가

        // 3. 레포지토리 호출 (Projection 리스트 조회)
        List<PostSearchProjection> allProjections = searchRepository.search(
                new PostSearchRequest(q, user.getId(), resolvedBoardId, blockedIds, afterScore, afterTime, afterId, pageSize));

        // 4. 페이징 처리 (hasNext 여부 확인 후 데이터 절단)
        boolean hasNext = allProjections.size() > pageSize;
        List<PostSearchProjection> contentProjections = hasNext ? allProjections.subList(0, pageSize) : allProjections;

        // 5. 벌크 조회를 위한 ID 추출
        List<Long> postIds = contentProjections.stream().map(PostSearchProjection::postId).toList();
        List<Long> authorIds = contentProjections.stream()
                .map(PostSearchProjection::authorId)
                .filter(Objects::nonNull) // 익명은 프로필 이미지 필요 없음
                .distinct().toList();


        // 6. 벌크 데이터 조회 (Map/Set 변환)
        Map<Long, Long> likeCounts = convertToMap(likeRepository.countByPostIds(postIds));
        Map<Long, Long> commentCounts = convertToMap(commentRepository.countByPostIds(postIds));
        Map<Long, String> userImageUrls = convertToStringMap(imageRepository.findProfileImagesByUserIds(authorIds));
        Map<Long, String> contentThumbnails = convertToStringMap(imageRepository.findFirstUrlsByPostIds(postIds));
        Map<Long, Integer> contentImageCounts = convertToIntegerMap(imageRepository.countImageByPostIds(postIds));

        Set<Long> likedPostIds = new HashSet<>(likeRepository.findLikedPostIdsByUserId(user.getId(), postIds));
        Set<Long> bookmarkedPostIds = new HashSet<>(bookmarkRepository.findBookmarkedPostIdsByUserId(user.getId(), postIds));

        // 7. 최종 DTO 조립
        List<SearchResultView> items = contentProjections.stream().map(p -> {
            BoardItem boardItem = new BoardItem(
                    p.postId(), p.contentPreview(), p.authorId(), p.authorName(),
                    p.category(), p.createdAt(), p.isAnonymous(),
                    likedPostIds.contains(p.postId()),
                    bookmarkedPostIds.contains(p.postId()),
                    likeCounts.getOrDefault(p.postId(), 0L),
                    commentCounts.getOrDefault(p.postId(), 0L),
                    p.viewCount(),
                    userImageUrls.get(p.authorId()),
                    p.scoreRounded(),
                    new BoardItem.PostInfo(contentThumbnails.get(p.postId()),
                            contentImageCounts.getOrDefault(p.postId(), 0)),
                    new BoardItem.PollInfo()
            );
            return new SearchResultView(boardItem, p.rawScore());
        }).toList();

        // 8. 다음 커서 생성
        String nextCursor = null;
        if (hasNext && !items.isEmpty()) {
            var last = items.get(items.size() - 1);
            nextCursor = safeEncode(Map.of(
                    "sc", last.score(),
                    "t", last.item().createdAt(),
                    "id", last.item().postId()
            ));
        }

        return new CursorPageResponse<>(items, hasNext, nextCursor);
    }

    private Map<Long, Long> convertToMap(List<Object[]> result) {
        return result.stream().collect(Collectors.toMap(r -> (Long) r[0], r -> (Long) r[1], (v1, v2) -> v1));
    }

    private Map<Long, String> convertToStringMap(List<Object[]> result) {
        return result.stream().collect(Collectors.toMap(r -> (Long) r[0], r -> (String) r[1], (v1, v2) -> v1));
    }

    private Map<Long, Integer> convertToIntegerMap(List<Object[]> result) {
        return result.stream().collect(Collectors.toMap(r -> (Long) r[0], r -> ((Number) r[1]).intValue(), (v1, v2) -> v1));
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

    @Transactional(readOnly = true)
    public List<String> suggest(String prefix, Long boardId) {
        String pfx = prefix == null ? "" : prefix.trim();
        if (pfx.isEmpty()) return List.of();

        // 1) 메모리 자동완성 우선 조회
        List<String> fast = memoryIndex.suggestPrefix(pfx, FAST_FIRST_MAX);

        // 2) 부족분 계산 (LIMIT에서 메모리 검색 결과 수를 뺌)
        int remain = LIMIT - fast.size();

        // [핵심 수정] 부족분이 없으면(remain <= 0) DB 조회 생략하고 바로 반환
        if (remain <= 0) {
            return fast.stream().limit(LIMIT).toList();
        }

        // 3) 부족할 때만 PGroonga(DB) 보충
        Long resolvedBoardId = (boardId != null && boardId == 1L) ? null : boardId;
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        List<Long> blockedIds = blockRepository.getBlockUsersByUserEmail(email)
                .stream().map(User::getId).toList();

        // 여기서 remain이 무조건 1 이상임이 보장됨
        List<String> db = searchRepository.suggest(pfx, resolvedBoardId, blockedIds, remain);

        // 4) 하이브리드 머지 및 대소문자 중복 제거
        // Key: 소문자 정규화된 문자열, Value: 실제 노출될 문자열
        Map<String, String> deduplicatedMap = new LinkedHashMap<>();

        // 메모리 결과 먼저 삽입 (우선순위)
        for (String s : fast) {
            deduplicatedMap.putIfAbsent(s.toLowerCase().trim(), s);
        }

        // DB 결과 삽입 (이미 소문자 기준 동일한 단어가 있다면 스킵됨)
        for (String s : db) {
            // DB에서 온 지저분한 특수문자나 소유격 한번 더 정리 (선택 사항)
            String cleaned = s.replaceAll("(?i)\\s*[''’]?s\\b", "").trim();
            deduplicatedMap.putIfAbsent(cleaned.toLowerCase(), cleaned);
        }

        return deduplicatedMap.values().stream()
                .limit(LIMIT)
                .toList();
    }

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

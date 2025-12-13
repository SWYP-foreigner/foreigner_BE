package core.domain.post.service.impl;

import core.domain.board.dto.BoardItem;
import core.domain.board.repository.BoardRepository;
import core.domain.post.dto.search.SearchResultView;
import core.domain.post.repository.PostSearchRepositoryCustom;
import core.domain.post.service.search.PostSearchService;
import core.domain.post.service.search.SuggestMemoryIndex;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.UserRepository;
import core.global.exception.BusinessException;
import core.global.enums.errorcode.CommunityErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.pagination.CursorPageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PostSearchImplTest {

    @InjectMocks
    private PostSearchService postSearchService;

    @Mock private PostSearchRepositoryCustom searchRepository;
    @Mock private BoardRepository boardRepository;
    @Mock private BlockRepository blockRepository;
    @Mock private UserRepository userRepository;
    @Mock private SuggestMemoryIndex memoryIndex;

    private final String email = "test@example.com";
    private User user;

    @BeforeEach
    void setUp() {
        // SecurityContext mocking
        SecurityContext context = mock(SecurityContext.class);
        Authentication authentication = mock(Authentication.class);
        when(context.getAuthentication()).thenReturn(authentication);
        when(authentication.getName()).thenReturn(email);
        SecurityContextHolder.setContext(context);

        user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        // 차단 유저 기본값
        when(blockRepository.getBlockUsersByUserEmail(email))
                .thenReturn(List.of());
    }

    // ===========================
    // search
    // ===========================

    @Test
    @DisplayName("search - 첫 페이지 조회, size+1 방식으로 hasNext와 nextCursor를 생성")
    void search_firstPage_success() {
        String q = "hello";
        Long boardId = 1L;       // 1L → 전체 (resolvedBoardId = null)
        String cursor = null;
        int size = 2;

        // SearchResultView / item mock
        BoardItem b1 = mock(BoardItem.class);
        BoardItem b2 = mock(BoardItem.class);
        BoardItem b3 = mock(BoardItem.class);

        Instant now = Instant.now();
        when(b1.createdAt()).thenReturn(now);
        when(b1.postId()).thenReturn(11L);
        when(b2.createdAt()).thenReturn(now.plusSeconds(1));
        when(b2.postId()).thenReturn(12L);
        when(b3.createdAt()).thenReturn(now.plusSeconds(2));
        when(b3.postId()).thenReturn(13L);

        // SearchResultView 는 진짜 객체로 생성
        SearchResultView v1 = new SearchResultView(b1, 0.9);
        SearchResultView v2 = new SearchResultView(b2, 0.8);
        SearchResultView v3 = new SearchResultView(b3, 0.7);

        // searchRepository.search → size+1 개 리턴
        when(searchRepository.search(
                eq(q),
                eq(1L),
                isNull(),        // boardId=1L → resolvedBoardId=null
                anyList(),       // blockedIds
                isNull(),        // afterTime
                isNull(),        // afterId
                eq(size + 1)
        )).thenReturn(List.of(v1, v2, v3));

        CursorPageResponse<SearchResultView> response =
                postSearchService.search(q, boardId, cursor, size);

        // then
        assertThat(response.items()).hasSize(2);    // size=2
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isNotNull();

        // 실제 items가 searchRepository에서 온 v1, v2인지 확인
        assertThat(response.items().get(0)).isSameAs(v1);
        assertThat(response.items().get(1)).isSameAs(v2);
    }

    @Test
    @DisplayName("search - boardId가 존재하지 않으면 BOARD_NOT_FOUND 예외")
    void search_boardNotFound_throwsException() {
        String q = "hello";
        Long boardId = 10L;
        String cursor = null;
        int size = 5;

        when(boardRepository.existsById(10L)).thenReturn(false);

        assertThatThrownBy(() -> postSearchService.search(q, boardId, cursor, size))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getError()).isEqualTo(CommunityErrorCode.BOARD_NOT_FOUND);
                });

        verify(searchRepository, never()).search(anyString(), anyLong(), any(), anyList(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("search - 결과가 pageSize 이하이면 hasNext=false, nextCursor=null")
    void search_noNextPage() {
        String q = "hello";
        Long boardId = 1L;
        String cursor = null;
        int size = 3;

        BoardItem item1 = mock(BoardItem.class);
        BoardItem item2 = mock(BoardItem.class);

        Instant now = Instant.now();
        when(item1.createdAt()).thenReturn(now);
        when(item1.postId()).thenReturn(11L);
        when(item2.createdAt()).thenReturn(now.plusSeconds(1));
        when(item2.postId()).thenReturn(12L);

        SearchResultView v1 = new SearchResultView(item1, 0.9);
        SearchResultView v2 = new SearchResultView(item2, 0.8);

        when(searchRepository.search(
                eq(q),
                eq(1L),
                isNull(),
                anyList(),
                isNull(),
                isNull(),
                eq(size + 1)
        )).thenReturn(List.of(v1, v2));   // 2개만 리턴

        CursorPageResponse<SearchResultView> response =
                postSearchService.search(q, boardId, cursor, size);

        assertThat(response.items()).hasSize(2);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("search - 로그인 유저를 찾지 못하면 USER_NOT_FOUND 예외")
    void search_userNotFound_throwsException() {
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postSearchService.search("q", 1L, null, 5))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getError()).isEqualTo(UserErrorCode.USER_NOT_FOUND);
                });

        verify(searchRepository, never()).search(anyString(), anyLong(), any(), anyList(), any(), any(), anyInt());
    }

    // ===========================
    // suggest
    // ===========================

    @Test
    @DisplayName("suggest - prefix가 비어 있으면 빈 리스트 반환")
    void suggest_emptyPrefix_returnsEmpty() {
        List<String> r1 = postSearchService.suggest(null, 1L);
        List<String> r2 = postSearchService.suggest("   ", 1L);

        assertThat(r1).isEmpty();
        assertThat(r2).isEmpty();

        verify(memoryIndex, never()).suggestPrefix(anyString(), anyInt());
        verify(searchRepository, never()).suggest(anyString(), any(), anyList(), anyInt());
    }

    @Test
    @DisplayName("suggest - 메모리 우선, 모자란 부분만 DB에서 채우고 중복 제거 후 LIMIT까지 반환")
    void suggest_mergeMemoryAndDb_success() {
        String prefix = "he";
        Long boardId = 1L; // 전체
        // 차단 유저: setUp에서 기본 empty

        // FAST_FIRST_MAX = 4, LIMIT = 7, DB_FALLBACK_MAX = 3
        // 메모리에서 3개, DB에서 3개 주고, 중복 1개 섞은 케이스
        when(memoryIndex.suggestPrefix(prefix.trim(), 4))
                .thenReturn(List.of("hello", "help", "heap"));

        when(searchRepository.suggest(eq(prefix.trim()), isNull(), anyList(), eq(3)))
                .thenReturn(List.of("health", "hello", "hear")); // "hello" 중복

        List<String> result = postSearchService.suggest(prefix, boardId);

        // 순서: 메모리 우선, 그 다음 DB (중복 제거)
        assertThat(result).containsExactly(
                "hello", "help", "heap", "health", "hear"
        );

        verify(memoryIndex).suggestPrefix("he", 4);
        verify(searchRepository).suggest(eq("he"), isNull(), anyList(), eq(3));
    }
}

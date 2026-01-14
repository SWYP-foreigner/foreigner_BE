package core.domain.post.service.impl;

import core.domain.board.repository.BoardRepository;
import core.domain.bookmark.repository.BookmarkRepository;
import core.domain.comment.repository.CommentRepository;
import core.domain.post.dto.search.PostSearchProjection;
import core.domain.post.dto.search.PostSearchRequest;
import core.domain.post.dto.search.SearchResultView;
import core.domain.post.repository.PostSearchRepositoryCustom;
import core.domain.post.service.search.PostSearchService;
import core.domain.post.service.search.PostSuggestIndex;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.UserRepository;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.like.repository.LikeRepository;
import core.global.exception.BusinessException;
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
    @Mock private PostSuggestIndex memoryIndex;
    @Mock private LikeRepository likeRepository;
    @Mock private ImageRepository imageRepository;
    @Mock private CommentRepository commentRepository;
    @Mock private BookmarkRepository bookmarkRepository;

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

        PostSearchProjection v1 = mock(PostSearchProjection.class);
        PostSearchProjection v2 = mock(PostSearchProjection.class);
        PostSearchProjection v3 = mock(PostSearchProjection.class);

        when(v1.postId()).thenReturn(11L);
        when(v1.createdAt()).thenReturn(Instant.now());
        when(v2.postId()).thenReturn(12L);
        when(v2.createdAt()).thenReturn(Instant.now().plusSeconds(1));
        when(v3.postId()).thenReturn(13L);
        when(v3.createdAt()).thenReturn(Instant.now().plusSeconds(2));

        when(searchRepository.search(any(PostSearchRequest.class)))
                .thenReturn(List.of(v1, v2, v3));

        CursorPageResponse<SearchResultView> response =
                postSearchService.search(q, boardId, cursor, size);

        // then
        assertThat(response.items()).hasSize(2);
        assertThat(response.hasNext()).isTrue();
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
                .isInstanceOf(BusinessException.class);

        verify(searchRepository, never()).search(any(PostSearchRequest.class));
    }

    @Test
    @DisplayName("search - 결과가 pageSize 이하이면 hasNext=false, nextCursor=null")
    void search_noNextPage() {
        String q = "hello";
        Long boardId = 1L;
        int size = 3;

        PostSearchProjection p1 = mock(PostSearchProjection.class);
        PostSearchProjection p2 = mock(PostSearchProjection.class);

        when(p1.postId()).thenReturn(11L);
        when(p1.createdAt()).thenReturn(Instant.now());
        when(p1.rawScore()).thenReturn(0.9);

        when(p2.postId()).thenReturn(12L);
        when(p2.createdAt()).thenReturn(Instant.now().plusSeconds(1));
        when(p2.rawScore()).thenReturn(0.8);

        // 3. Repository는 Projection 리스트를 반환함
        when(searchRepository.search(any(PostSearchRequest.class)))
                .thenReturn(List.of(p1, p2));

        CursorPageResponse<SearchResultView> response =
                postSearchService.search(q, boardId, null, size);

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

        verify(searchRepository, never()).search(any(PostSearchRequest.class));
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
        // 1. 준비
        String prefix = "he";
        Long boardId = 1L; // 서비스 로직상 1L은 null(전체)로 처리됨

        // 서비스 상수: LIMIT = 9, FAST_FIRST_MAX = 9
        // 메모리에서 3개를 반환한다고 가정
        List<String> memoryResults = List.of("hello", "help", "heap");
        when(memoryIndex.suggestPrefix(eq("he"), eq(9))) // FAST_FIRST_MAX는 9임
                .thenReturn(memoryResults);

        // remain 계산: 9(LIMIT) - 3(memoryResults) = 6
        // DB에서는 6개를 요청하게 됨
        List<String> dbResults = List.of("health", "hello", "hear"); // "hello" 중복
        when(searchRepository.suggest(eq("he"), isNull(), anyList(), eq(6))) // remain은 6
                .thenReturn(dbResults);

        // 2. 실행
        List<String> result = postSearchService.suggest(prefix, boardId);

        // 3. 검증
        // 순서: 메모리(hello, help, heap) -> DB(health, hear / hello는 중복제거)
        assertThat(result).containsExactly(
                "hello", "help", "heap", "health", "hear"
        );

        // 정확한 파라미터로 호출되었는지 확인
        verify(memoryIndex).suggestPrefix("he", 9);
        verify(searchRepository).suggest(eq("he"), isNull(), anyList(), eq(6));
    }
}

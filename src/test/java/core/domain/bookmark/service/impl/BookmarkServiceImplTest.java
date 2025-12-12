package core.domain.bookmark.service.impl;

import core.domain.bookmark.dto.BookmarkItem;
import core.domain.bookmark.entity.Bookmark;
import core.domain.bookmark.repository.BookmarkRepository;
import core.domain.bookmark.service.BookmarkService;
import core.domain.comment.repository.CommentRepository;
import core.domain.post.entity.Post;
import core.domain.post.repository.PostRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.entity.image.repository.ImageRepository;
import core.global.enums.ImageType;
import core.global.enums.LikeType;
import core.global.exception.BusinessException;
import core.global.enums.errorcode.CommunityErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.entity.like.repository.LikeRepository;
import core.global.pagination.CursorCodec;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookmarkServiceImplTest {

    @InjectMocks
    private BookmarkServiceImpl bookmarkService;

    @Mock private BookmarkRepository bookmarkRepository;
    @Mock private UserRepository userRepository;
    @Mock private PostRepository postRepository;
    @Mock private LikeRepository likeRepository;
    @Mock private CommentRepository commentRepository;
    @Mock private ImageRepository imageRepository;

    private final String email = "test@example.com";

    @BeforeEach
    void setUp() {
        SecurityContext context = mock(SecurityContext.class);
        Authentication authentication = mock(Authentication.class);
        when(context.getAuthentication()).thenReturn(authentication);
        when(authentication.getName()).thenReturn(email);
        SecurityContextHolder.setContext(context);
    }

    // ===========================
    // getMyBookmarks
    // ===========================

    @Test
    @DisplayName("getMyBookmarks - 북마크가 없으면 빈 페이지 반환")
    void getMyBookmarks_empty() {
        int size = 10;
        String cursor = null;

        Slice<Bookmark> emptySlice = new SliceImpl<>(List.of());
        when(bookmarkRepository.findByUserEmailOrderByIdDesc(eq(email), any(Pageable.class)))
                .thenReturn(emptySlice);

        CursorPageResponse<BookmarkItem> response = bookmarkService.getMyBookmarks(size, cursor);

        assertThat(response.items()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();

        verify(likeRepository, never()).countByRelatedIds(any(), anyList());
        verify(commentRepository, never()).countByPostIds(anyList());
    }

    @Test
    @DisplayName("getMyBookmarks - 북마크 목록, 좋아요 수/댓글 수/이미지/좋아요 여부 매핑 및 커서 페이징")
    void getMyBookmarks_success_withPaginationAndMapping() {
        int size = 2;
        String cursor = null;

        // 북마크 / 게시글 / 작성자 mock
        Bookmark b1 = mock(Bookmark.class);
        Bookmark b2 = mock(Bookmark.class);
        Bookmark b3 = mock(Bookmark.class);

        when(b1.getId()).thenReturn(101L);
        when(b2.getId()).thenReturn(102L);
        when(b3.getId()).thenReturn(103L);

        Post p1 = mock(Post.class);
        Post p2 = mock(Post.class);
        Post p3 = mock(Post.class);

        when(b1.getPost()).thenReturn(p1);
        when(b2.getPost()).thenReturn(p2);
        when(b3.getPost()).thenReturn(p3);

        when(p1.getId()).thenReturn(11L);
        when(p2.getId()).thenReturn(12L);
        when(p3.getId()).thenReturn(13L);

        User author1 = mock(User.class);
        User author2 = mock(User.class);
        when(author1.getId()).thenReturn(1L);
        when(author2.getId()).thenReturn(2L);

        when(author1.getFirstName()).thenReturn("John");
        when(author1.getLastName()).thenReturn("Doe");
        when(author2.getFirstName()).thenReturn("Alice");
        when(author2.getLastName()).thenReturn("Kim");

        when(p1.getAuthor()).thenReturn(author1);
        when(p2.getAuthor()).thenReturn(author1);
        when(p3.getAuthor()).thenReturn(author2);

        when(p1.getContent()).thenReturn("content1");
        when(p2.getContent()).thenReturn("content2");
        when(p3.getContent()).thenReturn("content3");

        Instant now = Instant.now();
        when(p1.getCreatedAt()).thenReturn(now);
        when(p2.getCreatedAt()).thenReturn(now);
        when(p3.getCreatedAt()).thenReturn(now);

        when(p1.getAnonymous()).thenReturn(false);
        when(p2.getAnonymous()).thenReturn(true);
        when(p3.getAnonymous()).thenReturn(false);

        when(p1.getCheckCount()).thenReturn(10L);
        when(p2.getCheckCount()).thenReturn(20L);
        when(p3.getCheckCount()).thenReturn(30L);

        // Slice: id desc 정렬 가정 → [b3, b2, b1] 리턴
        Slice<Bookmark> slice = new SliceImpl<>(
                List.of(b3, b2, b1),
                PageRequest.of(0, size + 1),
                false
        );
        when(bookmarkRepository.findByUserEmailOrderByIdDesc(eq(email), any(Pageable.class)))
                .thenReturn(slice);

        List<Long> pagePostIds = List.of(13L, 12L);

        // 좋아요 수
        when(likeRepository.countByRelatedIds(LikeType.POST, pagePostIds))
                .thenReturn(List.of(
                        new Object[]{13L, 5L},
                        new Object[]{12L, 3L}
                ));

        // 댓글 수
        when(commentRepository.countByPostIds(pagePostIds))
                .thenReturn(List.of(
                        new Object[]{13L, 7L},
                        new Object[]{12L, 4L}
                ));

        // 유저 프로필 이미지
        when(imageRepository.findFirstUrlByRelatedIds(eq(ImageType.USER), anyList()))
                .thenReturn(List.of(
                        new Object[]{1L, "user1.png"},
                        new Object[]{2L, "user2.png"}
                ));

        // 게시글 이미지
        when(imageRepository.findAllUrlsByRelatedIds(ImageType.POST, pagePostIds))
                .thenReturn(List.of(
                        new Object[]{13L, "p3-1.png"},
                        new Object[]{12L, "p2-1.png"}
                ));

        // 내가 좋아요한 게시글
        when(likeRepository.findMyLikedRelatedIdsByEmail(email, LikeType.POST, pagePostIds))
                .thenReturn(List.of(13L));

        CursorPageResponse<BookmarkItem> response = bookmarkService.getMyBookmarks(size, cursor);

        // size=2 이므로 2개만 반환, hasNext=true, nextCursor는 두 번째 북마크 id(=b2)
        assertThat(response.items()).hasSize(2);
        assertThat(response.hasNext()).isTrue();

        BookmarkItem item0 = response.items().get(0); // b3
        BookmarkItem item1 = response.items().get(1); // b2

        assertThat(item0.bookmarkId()).isEqualTo(103L);
        assertThat(item0.postId()).isEqualTo(13L);
        assertThat(item0.authorId()).isEqualTo(2L);
        assertThat(item0.authorName()).isEqualTo("Alice Kim");
        assertThat(item0.likeCount()).isEqualTo(5L);
        assertThat(item0.commentCount()).isEqualTo(7L);
        assertThat(item0.userImage()).isEqualTo("user2.png");
        assertThat(item0.postImages()).containsExactly("p3-1.png");
        assertThat(item0.isLiked()).isTrue();

        assertThat(item1.bookmarkId()).isEqualTo(102L);
        assertThat(item1.postId()).isEqualTo(12L);
        assertThat(item1.authorId()).isEqualTo(1L);
        // p2는 anonymous=true 이므로 authorName은 "Anonymity"
        assertThat(item1.authorName()).isEqualTo("Anonymity");
        assertThat(item1.isAnonymous()).isTrue();
        assertThat(item1.likeCount()).isEqualTo(3L);
        assertThat(item1.commentCount()).isEqualTo(4L);
        assertThat(item1.userImage()).isEqualTo("user1.png");
        assertThat(item1.isLiked()).isFalse();

        String expectedCursor = CursorCodec.encodeId(102L);
        assertThat(response.nextCursor()).isEqualTo(expectedCursor);
    }

    // ===========================
    // addBookmark
    // ===========================

    @Test
    @DisplayName("addBookmark - 정상 추가")
    void addBookmark_success() {
        Long postId = 10L;

        User user = mock(User.class);
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        when(bookmarkRepository.findByUserEmailAndPostId(email, postId))
                .thenReturn(Optional.empty());

        Post post = mock(Post.class);
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));

        bookmarkService.addBookmark(postId);

        verify(bookmarkRepository).save(any(Bookmark.class));
    }

    @Test
    @DisplayName("addBookmark - 사용자 없음이면 USER_NOT_FOUND 예외")
    void addBookmark_userNotFound_throwsException() {
        Long postId = 10L;

        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookmarkService.addBookmark(postId))
                .isInstanceOf(BusinessException.class);
        // 에러코드까지 보고 싶으면 BusinessException 에서 꺼내서 검증
    }

    @Test
    @DisplayName("addBookmark - 이미 북마크 되어 있으면 BOOKMARK_ALREADY_EXIST 예외")
    void addBookmark_alreadyExists_throwsException() {
        Long postId = 10L;

        User user = mock(User.class);
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        Bookmark existing = mock(Bookmark.class);
        when(bookmarkRepository.findByUserEmailAndPostId(email, postId))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> bookmarkService.addBookmark(postId))
                .isInstanceOf(BusinessException.class);

        verify(postRepository, never()).findById(anyLong());
        verify(bookmarkRepository, never()).save(any());
    }

    @Test
    @DisplayName("addBookmark - 게시글이 없으면 POST_NOT_FOUND 예외")
    void addBookmark_postNotFound_throwsException() {
        Long postId = 10L;

        User user = mock(User.class);
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        when(bookmarkRepository.findByUserEmailAndPostId(email, postId))
                .thenReturn(Optional.empty());

        when(postRepository.findById(postId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookmarkService.addBookmark(postId))
                .isInstanceOf(BusinessException.class);

        verify(bookmarkRepository, never()).save(any());
    }

    // ===========================
    // removeBookmark
    // ===========================

    @Test
    @DisplayName("removeBookmark - 정상 삭제 (존재 여부에 상관없이 deleteByUserEmailAndPostId 호출)")
    void removeBookmark_success() {
        Long postId = 10L;

        bookmarkService.removeBookmark(postId);

        verify(bookmarkRepository).deleteByUserEmailAndPostId(email, postId);
    }
}

package core.domain.post.service.impl;

import core.domain.board.dto.BoardItem;
import core.domain.board.entity.Board;
import core.domain.board.repository.BoardRepository;
import core.domain.post.dto.admin.PostReportRequest;
import core.domain.post.dto.comunity.PostDetailResponse;
import core.domain.post.dto.comunity.PostUpdateRequest;
import core.domain.post.dto.comunity.PostWriteRequest;
import core.domain.post.dto.comunity.UserPostItem;
import core.domain.post.entity.BlockPost;
import core.domain.post.entity.Post;
import core.domain.post.entity.PostReport;
import core.domain.post.repository.BlockPostRepository;
import core.domain.post.repository.PostReportRepository;
import core.domain.post.repository.PostRepository;
import core.domain.user.entity.BlockUser;
import core.domain.user.entity.Follow;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.FollowRepository;
import core.domain.user.repository.UserRepository;
import core.domain.user.service.UserRoleDetectService;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageService;
import core.global.entity.like.entity.Like;
import core.global.entity.like.repository.LikeRepository;
import core.global.enums.BoardCategory;
import core.global.enums.FollowStatus;
import core.global.enums.LikeType;
import core.global.enums.SortOption;
import core.global.enums.errorcode.CommonErrorCode;
import core.global.enums.errorcode.CommunityErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import core.global.pagination.CursorPageResponse;
import core.global.service.ForbiddenWordService;
import core.global.service.TranslationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static core.global.enums.errorcode.CommunityErrorCode.BOARD_NOT_FOUND;
import static core.global.enums.errorcode.CommunityErrorCode.POST_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PostServiceImplTest {

    private final String email = "test@example.com";
    @InjectMocks
    private PostServiceImpl postService;
    @Mock
    private PostRepository postRepository;
    @Mock
    private BoardRepository boardRepository;
    @Mock
    private LikeRepository likeRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private ForbiddenWordService forbiddenWordService;
    @Mock
    private ImageService imageService;
    @Mock
    private BlockRepository blockRepository;
    @Mock
    private BlockPostRepository blockPostRepository;
    @Mock
    private TranslationService translationService;
    @Mock
    private UserRoleDetectService userRoleDetectService;
    @Mock
    private FollowRepository followRepository;
    @Mock
    private PostReportRepository postReportRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
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
        lenient().when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
    }

    // =========================================
    // getPostList
    // =========================================

    @Test
    @DisplayName("getPostList - 존재하지 않는 게시판이면 BOARD_NOT_FOUND 예외")
    void getPostList_boardNotFound_throwsException() {
        Long boardId = 10L;
        when(boardRepository.existsById(boardId)).thenReturn(false);

        assertThatThrownBy(() ->
                postService.getPostList(boardId, SortOption.LATEST, null, 10)
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getError()).isEqualTo(BOARD_NOT_FOUND);
                });

        verify(postRepository, never()).findLatestPosts(anyLong(), any(), any(), any(), anyInt(), any());
        verify(postRepository, never()).findPopularPosts(anyLong(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("getPostList - 정상 조회(LATEST), rows가 비어있으면 빈 페이지 반환")
    void getPostList_latest_empty() {
        Long boardId = 1L; // 전체 게시판 (resolvedBoardId=null)
        int size = 5;

        // user는 @BeforeEach에서 stub
        when(postRepository.findLatestPosts(
                anyLong(),
                isNull(),
                isNull(),
                isNull(),
                eq(size + 1),
                isNull()
        )).thenReturn(List.of());

        CursorPageResponse<BoardItem> response =
                postService.getPostList(boardId, SortOption.LATEST, null, size);

        assertThat(response.items()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    // =========================================
    // getPostDetail
    // =========================================

    @Test
    @DisplayName("getPostDetail - 로그인 유저 없으면 USER_NOT_FOUND 예외")
    void getPostDetail_userNotFound_throwsException() {
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postService.getPostDetail(1L, false))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getError()).isEqualTo(UserErrorCode.USER_NOT_FOUND);
                });

        verify(postRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("getPostDetail - 게시글이 없으면 POST_NOT_FOUND 예외")
    void getPostDetail_postNotFound_throwsException() {
        when(postRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postService.getPostDetail(1L, false))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getError()).isEqualTo(POST_NOT_FOUND);
                });

        verify(postRepository, never()).incrementViewCount(anyLong());
    }

    @Test
    @DisplayName("getPostDetail - 차단 관계이면 BLOCKED_USER_POST 예외")
    void getPostDetail_blockedUser_throwsException() {
        User author = mock(User.class);
        when(author.getEmail()).thenReturn("other@example.com");

        Post post = mock(Post.class);
        when(post.getAuthor()).thenReturn(author);
        when(postRepository.findById(1L)).thenReturn(Optional.of(post));

        when(blockRepository.existsBlockedByEmail(email, "other@example.com")).thenReturn(true);

        assertThatThrownBy(() -> postService.getPostDetail(1L, false))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getError()).isEqualTo(CommunityErrorCode.BLOCKED_USER_POST);
                });

        verify(postRepository, never()).incrementViewCount(anyLong());
    }

    @Test
    @DisplayName("getPostDetail - translate=false일 때 단순 상세 조회 + 조회수 증가")
    void getPostDetail_translateFalse_success() {
        User author = mock(User.class);
        when(author.getEmail()).thenReturn("other@example.com");

        Post post = mock(Post.class);
        when(post.getAuthor()).thenReturn(author);
        when(postRepository.findById(1L)).thenReturn(Optional.of(post));

        PostDetailResponse detail = mock(PostDetailResponse.class);
        when(postRepository.findPostDetail(email, 1L)).thenReturn(detail);

        PostDetailResponse result = postService.getPostDetail(1L, false);

        assertThat(result).isEqualTo(detail);
        verify(postRepository).incrementViewCount(1L);
        verify(translationService, never()).translatePost(anyString(), anyString());
    }

    // =========================================
    // writePost
    // =========================================

    @Test
    @DisplayName("writePost - boardId가 1이면 NOT_AVAILABLE_WRITE 예외")
    void writePost_notAvailableBoard_throwsException() {
        PostWriteRequest request = new PostWriteRequest("content", false, List.of());

        assertThatThrownBy(() -> postService.writePost(1L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getError()).isEqualTo(CommunityErrorCode.NOT_AVAILABLE_WRITE);
                });

        verify(boardRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("writePost - 게시판이 없으면 BOARD_NOT_FOUND 예외")
    void writePost_boardNotFound_throwsException() {
        Long boardId = 10L;
        PostWriteRequest request = new PostWriteRequest("content", false, List.of());

        when(boardRepository.findById(boardId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postService.writePost(boardId, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getError()).isEqualTo(BOARD_NOT_FOUND);
                });

        verify(postRepository, never()).save(any(Post.class));
    }

    @Test
    @DisplayName("writePost - 금지어 포함 시 FORBIDDEN_WORD_DETECTED 예외")
    void writePost_forbiddenWord_throwsException() {
        Long boardId = 2L;
        PostWriteRequest request = new PostWriteRequest("bad word", false, List.of());

        Board board = mock(Board.class);
        when(board.getCategory()).thenReturn(BoardCategory.FREE_TALK);
        when(boardRepository.findById(boardId)).thenReturn(Optional.of(board));

        when(forbiddenWordService.containsForbiddenWord("bad word"))
                .thenReturn(List.of("bad"));

        assertThatThrownBy(() -> postService.writePost(boardId, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getError()).isEqualTo(CommonErrorCode.FORBIDDEN_WORD_DETECTED);
                });

        verify(postRepository, never()).save(any(Post.class));
    }

//    @Test
//    @DisplayName("writePost - 도배(5분 내 게시글 수 초과)면 TOO_MANY_POSTS 예외")
//    void writePost_flooding_throwsException() {
//        Long boardId = 2L;
//        PostWriteRequest request = new PostWriteRequest("hello", false, List.of());
//
//        Board board = mock(Board.class);
//        when(board.getCategory()).thenReturn(BoardCategory.FREE_TALK);
//        when(boardRepository.findById(boardId)).thenReturn(Optional.of(board));
//
//        when(forbiddenWordService.containsForbiddenWord("hello"))
//                .thenReturn(List.of());
//        when(postRepository.existsByAuthorEmailAndContentAndCreatedAtAfter(anyString(), anyString(), any()))
//                .thenReturn(false);
//        when(postRepository.countByAuthorEmailAndCreatedAtAfter(anyString(), any()))
//                .thenReturn(3L); // FLOOD_MAX_POSTS = 3 이상
//
//        assertThatThrownBy(() -> postService.writePost(boardId, request))
//                .isInstanceOf(BusinessException.class)
//                .satisfies(e -> {
//                    BusinessException be = (BusinessException) e;
//                    assertThat(be.getError()).isEqualTo(CommunityErrorCode.TOO_MANY_POSTS);
//                });
//
//        verify(postRepository, never()).save(any(Post.class));
//    }

    @Test
    @DisplayName("writePost - 정상 작성 시 Post 저장 및 이미지 저장, 팔로워 알림 발행")
    void writePost_success() {
        Long boardId = 2L;
        PostWriteRequest request = new PostWriteRequest("hello", false, List.of("img1"));

        Board board = mock(Board.class);
        when(board.getCategory()).thenReturn(BoardCategory.FREE_TALK);
        when(boardRepository.findById(boardId)).thenReturn(Optional.of(board));

        when(forbiddenWordService.containsForbiddenWord("hello"))
                .thenReturn(List.of());
        when(postRepository.existsByAuthorEmailAndContentAndCreatedAtAfter(anyString(), anyString(), any()))
                .thenReturn(false);
        when(postRepository.countByAuthorEmailAndCreatedAtAfter(anyString(), any()))
                .thenReturn(0L);

        // user
        when(user.getId()).thenReturn(1L);

        // postRepository.save → 넘긴 Post 그대로 리턴
        Post savedPost = mock(Post.class);
        when(savedPost.getId()).thenReturn(100L);
        when(savedPost.getAuthor()).thenReturn(user);

        when(postRepository.save(any(Post.class))).thenReturn(savedPost);

        // 팔로워: 1명 있다고 가정
        User followerUser = mock(User.class);
        when(followerUser.getId()).thenReturn(2L);

        Follow follow = mock(Follow.class);
        when(follow.getUser()).thenReturn(followerUser);
        when(follow.getFollowing()).thenReturn(user);
        when(followRepository.findAllByFollowingAndStatus(user, FollowStatus.ACCEPTED))
                .thenReturn(List.of(follow));

        postService.writePost(boardId, request);

        verify(postRepository).save(any(Post.class));
        verify(imageService).savePostImages(anyLong(), eq(request.imageUrls()));
        // PostCreatedEvent, NotificationEvent 발행 여부
        verify(eventPublisher, atLeastOnce()).publishEvent(any(Object.class));

    }

    // =========================================
    // updatePost
    // =========================================

    @Test
    @DisplayName("updatePost - 작성자가 아니면 POST_EDIT_FORBIDDEN 예외")
    void updatePost_notAuthor_throwsException() {
        Long postId = 1L;
        PostUpdateRequest request = new PostUpdateRequest("updated", List.of(), List.of());

        when(forbiddenWordService.containsForbiddenWord("updated"))
                .thenReturn(List.of());

        Post post = mock(Post.class);
        User author = mock(User.class);
        when(author.getEmail()).thenReturn("other@example.com");
        when(post.getAuthor()).thenReturn(author);
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));

        assertThatThrownBy(() -> postService.updatePost(postId, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getError()).isEqualTo(CommunityErrorCode.POST_EDIT_FORBIDDEN);
                });

        verify(post, never()).changeContent(anyString());
        verify(imageService, never()).updatePostImages(anyLong(), anyList(), anyList());
    }

    // =========================================
    // deletePost
    // =========================================

    @Test
    @DisplayName("deletePost - 작성자가 아니면 POST_DELETE_FORBIDDEN 예외")
    void deletePost_notAuthor_throwsException() {
        Long postId = 1L;

        Post post = mock(Post.class);
        User author = mock(User.class);
        when(author.getEmail()).thenReturn("other@example.com");
        when(post.getAuthor()).thenReturn(author);
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));

        assertThatThrownBy(() -> postService.deletePost(postId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getError()).isEqualTo(CommunityErrorCode.POST_DELETE_FORBIDDEN);
                });

        verify(postRepository, never()).delete(any(Post.class));
    }

    // =========================================
    // addLike / removeLike
    // =========================================

    @Test
    @DisplayName("addLike - 이미 좋아요 되어 있으면 LIKE_ALREADY_EXIST 예외")
    void addLike_alreadyExists_throwsException() {
        Long postId = 1L;

        Like like = mock(Like.class);
        when(likeRepository.findLikeByUserEmailAndType(email, postId, LikeType.POST))
                .thenReturn(Optional.of(like));

        assertThatThrownBy(() -> postService.addLike(postId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getError()).isEqualTo(CommunityErrorCode.LIKE_ALREADY_EXIST);
                });

        verify(likeRepository, never()).save(any(Like.class));
    }

    @Test
    @DisplayName("removeLike - 정상 삭제 시 deleteByUserEmailAndIdAndType 호출")
    void removeLike_success() {
        Long postId = 1L;

        postService.removeLike(postId);

        verify(likeRepository).deleteByUserEmailAndIdAndType(email, postId, LikeType.POST);
    }

    // =========================================
    // getMyPostList
    // =========================================

    @Test
    @DisplayName("getMyPostList - 첫 페이지, 결과가 없으면 빈 페이지 반환")
    void getMyPostList_empty() {
        int size = 5;
        String cursor = null;

        when(postRepository.findMyPostsFirstByEmail(email, size + 1))
                .thenReturn(List.of());

        CursorPageResponse<UserPostItem> response =
                postService.getMyPostList(cursor, size);

        assertThat(response.items()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    // =========================================
    // isAnonymousAvaliable
    // =========================================

    @Test
    @DisplayName("isAnonymousAvaliable - 게시글이 없으면 POST_NOT_FOUND 예외")
    void isAnonymousAvaliable_postNotFound_throwsException() {
        when(postRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postService.isAnonymousAvaliable(1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getError()).isEqualTo(POST_NOT_FOUND);
                });
    }

    // =========================================
    // blockUser / blockPost
    // =========================================

    @Test
    @DisplayName("blockUser - 자기 자신은 차단 불가(CANNOT_BLOCK)")
    void blockUser_self_throwsException() {
        User target = mock(User.class);
        when(target.getEmail()).thenReturn(email);
        when(postRepository.findUserByPostId(1L)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> postService.blockUser(1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getError()).isEqualTo(UserErrorCode.CANNOT_BLOCK);
                });

        verify(blockRepository, never()).save(any(BlockUser.class));
    }

    @Test
    @DisplayName("blockPost - 내 글이면 차단 불가(CANNOT_BLOCK)")
    void blockPost_ownPost_throwsException() {
        Post post = mock(Post.class);
        when(postRepository.findById(1L)).thenReturn(Optional.of(post));
        when(post.getAuthor()).thenReturn(user);
        when(user.getEmail()).thenReturn(email);

        assertThatThrownBy(() -> postService.blockPost(1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getError()).isEqualTo(UserErrorCode.CANNOT_BLOCK);
                });

        verify(blockPostRepository, never()).save(any(BlockPost.class));
    }


    // =========================================
    // reportPost
    // =========================================

    @Test
    @DisplayName("reportPost - 신고자가 존재하지 않으면 USER_NOT_FOUND 예외")
    void reportPost_reporterNotFound_throwsException() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        PostReportRequest request = new PostReportRequest("SPAM", "detail");

        assertThatThrownBy(() -> postService.reportPost(1L, 10L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getError()).isEqualTo(UserErrorCode.USER_NOT_FOUND);
                });

        verify(postReportRepository, never()).save(any(PostReport.class));
    }

    @Test
    @DisplayName("reportPost - 같은 게시글을 중복 신고하면 DUPLICATE_REPORT 예외")
    void reportPost_duplicateReport_throwsException() {
        User reporter = mock(User.class);
        Post post = mock(Post.class);

        when(userRepository.findById(1L)).thenReturn(Optional.of(reporter));
        when(postRepository.findById(10L)).thenReturn(Optional.of(post));
        when(postReportRepository.existsByReporterAndPost(reporter, post)).thenReturn(true);

        PostReportRequest request = new PostReportRequest("SPAM", "detail");

        assertThatThrownBy(() -> postService.reportPost(1L, 10L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getError()).isEqualTo(CommunityErrorCode.DUPLICATE_REPORT);
                });

        verify(postReportRepository, never()).save(any(PostReport.class));
    }
}

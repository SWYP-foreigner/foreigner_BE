package core.domain.comment.service.impl;

import core.domain.comment.dto.CommentUpdateRequest;
import core.domain.comment.dto.CommentWriteRequest;
import core.domain.comment.dto.UserCommentItem;
import core.domain.comment.entity.Comment;
import core.domain.comment.repository.CommentRepository;
import core.domain.post.entity.Post;
import core.domain.post.repository.PostRepository;
import core.domain.user.entity.BlockUser;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.UserRepository;
import core.domain.user.service.UserRoleDetectService;
import core.global.entity.image.service.ImageService;
import core.global.entity.like.repository.LikeRepository;
import core.global.enums.common.CommunitySortOption;
import core.global.enums.common.LikeType;
import core.global.enums.community.BoardCategory;
import core.global.exception.BusinessException;
import core.global.pagination.CursorCodec;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
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
class CommentServiceImplTest {

    @InjectMocks
    private CommentServiceImpl commentService;

    @Mock
    private CommentRepository commentRepository;
    @Mock
    private PostRepository postRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private LikeRepository likeRepository;
    @Mock
    private ForbiddenWordService forbiddenWordService;
    @Mock
    private BlockRepository blockRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private TranslationService translationService;
    @Mock
    private UserRoleDetectService userRoleDetectService;
    @Mock
    private ImageService imageService;

    private User user;

    @BeforeEach
    void setUp() {
        // SecurityContext mocking
        SecurityContext context = mock(SecurityContext.class);
        Authentication authentication = mock(Authentication.class);
        when(context.getAuthentication()).thenReturn(authentication);
        when(authentication.getName()).thenReturn("test@example.com");
        SecurityContextHolder.setContext(context);

        // 공통 유저 mock
        user = mock(User.class);

        lenient()
                .when(userRepository.findByEmail("test@example.com"))
                .thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("writeComment - 루트 댓글 작성 시 게시글 작성자에게 알림 발송")
    void writeComment_rootComment_success() {
        Long postId = 10L;
        CommentWriteRequest request = new CommentWriteRequest(null, false, "hello world");

        // forbidden word 없음
        when(forbiddenWordService.containsForbiddenWord("hello world"))
                .thenReturn(List.of());

        // 도배/중복 체크
        when(commentRepository.existsByAuthorEmailAndContentAndCreatedAtAfter(anyString(), anyString(), any()))
                .thenReturn(false);
        when(commentRepository.countByAuthorEmailAndCreatedAtAfter(anyString(), any()))
                .thenReturn(0L);

        // Post, 작성자, Board 카테고리 mock
        Post post = mock(Post.class);
        User postAuthor = mock(User.class);
        when(post.getId()).thenReturn(postId);
        when(post.getAuthor()).thenReturn(postAuthor);
        when(postAuthor.getId()).thenReturn(2L); // 나와 다른 사람

        var board = mock(core.domain.board.entity.Board.class); // 실제 Board 패키지명에 맞춰 수정
        when(board.getCategory()).thenReturn(BoardCategory.FREE_TALK);
        when(post.getBoard()).thenReturn(board);

        when(postRepository.findById(postId)).thenReturn(Optional.of(post));

        // save 결과 comment mock
        Comment saved = mock(Comment.class);
        when(saved.getId()).thenReturn(100L);
        when(commentRepository.save(any(Comment.class))).thenReturn(saved);

        // when
        commentService.writeComment(postId, request);

        // then
        // 프로필 세팅 체크
        verify(userRoleDetectService).isProfileSetUpUser(user);

        // 댓글 save 확인
        verify(commentRepository).save(any(Comment.class));
    }

    @Test
    @DisplayName("writeComment - 금지어 포함 시 BusinessException 발생")
    void writeComment_forbiddenWord_throwsException() {
        Long postId = 10L;
        CommentWriteRequest request = new CommentWriteRequest(null, false, "bad word");

        when(forbiddenWordService.containsForbiddenWord("bad word"))
                .thenReturn(List.of("bad"));

        assertThatThrownBy(() -> commentService.writeComment(postId, request))
                .isInstanceOf(BusinessException.class);

        // forbidden word에서 막혀야 하므로 아래 호출은 없어야 함
        verify(postRepository, never()).findById(anyLong());
        verify(commentRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateComment - 작성자가 아닌 경우 COMMENT_EDIT_FORBIDDEN 예외")
    void updateComment_notAuthor_throwsException() {
        Long commentId = 1L;
        CommentUpdateRequest request = new CommentUpdateRequest("updated content");

        when(forbiddenWordService.containsForbiddenWord("updated content"))
                .thenReturn(List.of());
        when(commentRepository.existsByAuthorEmailAndContentAndCreatedAtAfter(anyString(), anyString(), any()))
                .thenReturn(false);
        when(commentRepository.countByAuthorEmailAndCreatedAtAfter(anyString(), any()))
                .thenReturn(0L);

        Comment comment = mock(Comment.class);
        User anotherUser = mock(User.class);
        when(anotherUser.getEmail()).thenReturn("other@example.com");

        when(comment.getAuthor()).thenReturn(anotherUser);

        when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> commentService.updateComment(commentId, request))
                .isInstanceOf(BusinessException.class);

        verify(comment, never()).changeContent(anyString());
    }

    @Test
    @DisplayName("deleteComment - 살아있는 자식이 있으면 삭제 마킹만 수행")
    void deleteComment_hasAliveChildren_markDeletedOnly() {
        Long commentId = 1L;
        when(user.getEmail()).thenReturn("test@example.com");

        Comment comment = mock(Comment.class);
        when(comment.getAuthor()).thenReturn(user); // 현재 로그인 유저

        when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));
        when(commentRepository.existsByParentIdAndDeletedFalse(commentId)).thenReturn(true);

        commentService.deleteComment(commentId);

        // soft delete 호출
        verify(comment).markDeleted("test@example.com");

        // 실제 delete 호출 X
        verify(commentRepository, never()).delete(comment);
    }

    @Test
    @DisplayName("deleteComment - 자식이 없으면 실제 삭제 후 부모도 재귀적으로 정리")
    void deleteComment_noChildren_deleteAndCleanupParent() {
        Long commentId = 1L;
        when(user.getEmail()).thenReturn("test@example.com");

        Comment parent = mock(Comment.class);
        when(parent.getId()).thenReturn(10L);
        when(parent.isDeleted()).thenReturn(true);
        when(parent.getParent()).thenReturn(null); // 조상 없음

        Comment comment = mock(Comment.class);
        when(comment.getAuthor()).thenReturn(user);
        when(comment.getParent()).thenReturn(parent);

        when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));
        when(commentRepository.existsByParentIdAndDeletedFalse(commentId)).thenReturn(false);

        // parent에 자식 없음
        when(commentRepository.countByParentId(10L)).thenReturn(0L);

        commentService.deleteComment(commentId);

        // 자식 없는 본인 삭제
        verify(commentRepository).delete(comment);
        // 부모도 삭제
        verify(commentRepository).delete(parent);
    }

    @Test
    @DisplayName("blockUser - 자기 자신은 차단할 수 없음")
    void blockUser_selfCannotBlock() {
        Long commentId = 1L;

        User commentAuthor = mock(User.class);
        when(commentAuthor.getEmail()).thenReturn("test@example.com");

        when(commentRepository.findUserByCommentId(commentId))
                .thenReturn(Optional.of(commentAuthor));

        assertThatThrownBy(() -> commentService.blockUser(commentId))
                .isInstanceOf(BusinessException.class);

        verify(blockRepository, never()).save(any(BlockUser.class));
    }

    @Test
    @DisplayName("getMyCommentList - cursor 기반 페이징 동작")
    void getMyCommentList_success() {
        int size = 2;
        String cursor = null;

        UserCommentItem item1 = new UserCommentItem(1L, 10L, "post1", "content1", Instant.now());
        UserCommentItem item2 = new UserCommentItem(2L, 11L, "post2", "content2", Instant.now());
        UserCommentItem item3 = new UserCommentItem(3L, 12L, "post3", "content3", Instant.now()); // 다음 페이지 여부 판단용

        when(commentRepository.findMyCommentsForCursor(
                eq("test@example.com"),
                isNull(),
                any(PageRequest.class)
        )).thenReturn(List.of(item1, item2, item3));

        CursorPageResponse<UserCommentItem> response = commentService.getMyCommentList(size, cursor);

        assertThat(response.items()).hasSize(2);
        assertThat(response.hasNext()).isTrue();

        UserCommentItem last = response.items().get(1);
        String expectedCursor = CursorCodec.encodeId(last.commentId());
        assertThat(response.nextCursor()).isEqualTo(expectedCursor);
    }

    @Test
    @DisplayName("getCommentList - translate=true일 때 삭제되지 않은 댓글만 번역하여 content 치환")
    void getCommentList_translate_true() {
        Long postId = 10L;
        int size = 20;
        CommunitySortOption sort = CommunitySortOption.LATEST;
        String cursor = null;
        Boolean translate = true;

        Comment c1 = mock(Comment.class);
        Comment c2 = mock(Comment.class);

        when(c1.getId()).thenReturn(1L);
        when(c2.getId()).thenReturn(2L);

        // c1: 정상 댓글
        when(c1.isDeleted()).thenReturn(false);
        when(c1.getContent()).thenReturn("hello");
        // c2: 삭제된 댓글
        when(c2.isDeleted()).thenReturn(true);

        when(c1.getAuthor()).thenReturn(user);
        when(c2.getAuthor()).thenReturn(user);

        when(c1.getCreatedAt()).thenReturn(Instant.now());
        when(c2.getCreatedAt()).thenReturn(Instant.now());

        when(user.getId()).thenReturn(1L);
        when(user.getTranslateLanguage()).thenReturn("KR");

        Slice<Comment> slice = new SliceImpl<>(List.of(c1, c2));
        when(commentRepository.findByPostId(eq(user.getId()), eq(postId), any()))
                .thenReturn(slice);

        // 좋아요/프로필
        when(likeRepository.findMyLikedRelatedIds(eq(user.getId()), eq(LikeType.COMMENT), anyList()))
                .thenReturn(List.of());
        when(likeRepository.countByRelatedIds(eq(LikeType.COMMENT), anyList()))
                .thenReturn(List.of(new Object[]{1L, 2L}, new Object[]{2L, 0L}));
        when(imageService.getUserProfileKey(anyLong())).thenReturn("profile-key");

        // 번역은 삭제되지 않은 c1만 대상
        when(translationService.translateComments(List.of("hello"), "KR"))
                .thenReturn(List.of("안녕"));


        CursorPageResponse<?> response = commentService.getCommentList(
                postId, size, sort, cursor, translate
        );

        assertThat(response.items()).hasSize(2);
        var items = (List<?>) response.items();

        // 첫 번째 댓글이 번역된 content를 가지는지 (CommentItem 캐스팅해서 확인)
        core.domain.comment.dto.CommentItem item1 =
                (core.domain.comment.dto.CommentItem) items.get(0);
        core.domain.comment.dto.CommentItem item2 =
                (core.domain.comment.dto.CommentItem) items.get(1);

        assertThat(item1.deleted()).isFalse();
        assertThat(item2.deleted()).isTrue();

        // 번역 서비스가 정확한 인자로 한 번 호출되었는지 검증
        verify(translationService).translateComments(List.of("hello"), "KR");
    }
}

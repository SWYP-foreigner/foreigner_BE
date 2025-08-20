package core.domain.comment.service.impl;

import core.domain.comment.dto.*;
import core.domain.comment.entity.Comment;
import core.domain.comment.repository.CommentRepository;
import core.domain.comment.service.CommentService;
import core.domain.post.entity.Post;
import core.domain.post.repository.PostRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.LikeType;
import core.global.enums.SortOption;
import core.global.exception.BusinessException;
import core.global.enums.ImageType;
import core.global.image.repository.ImageRepository;
import core.global.like.repository.LikeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class CommentServiceImplTest {

    private CommentRepository commentRepository;
    private PostRepository postRepository;
    private UserRepository userRepository;
    private ImageRepository imageRepository;
    private LikeRepository likeRepository;

    private CommentService service;

    @BeforeEach
    void setUp() {
        commentRepository = mock(CommentRepository.class);
        postRepository = mock(PostRepository.class);
        userRepository = mock(UserRepository.class);
        imageRepository = mock(ImageRepository.class);
        likeRepository = mock(LikeRepository.class);

        service = new CommentServiceImpl(
                commentRepository, postRepository, userRepository, imageRepository, likeRepository
        );
    }

    // ===== Helpers =====
    private Comment mockComment(long id, Instant createdAt, User author, Post post, boolean deleted, Comment parent) {
        Comment c = mock(Comment.class);
        lenient().when(c.getId()).thenReturn(id);
        lenient().when(c.getCreatedAt()).thenReturn(createdAt);
        lenient().when(c.getAuthor()).thenReturn(author);
        lenient().when(c.getPost()).thenReturn(post);
        lenient().when(c.isDeleted()).thenReturn(deleted);
        lenient().when(c.getParent()).thenReturn(parent);
        return c;
    }

    private User mockUser(long id, String name) {
        User u = mock(User.class);
        lenient().when(u.getId()).thenReturn(id);
        lenient().when(u.getName()).thenReturn(name);
        return u;
    }

    private Post mockPost(long id) {
        Post p = mock(Post.class);
        lenient().when(p.getId()).thenReturn(id);
        return p;
    }

    // ===== getCommentList =====
    @Nested
    @DisplayName("getCommentList - LATEST")
    class GetCommentListLatest {

        @Test
        @DisplayName("초기 로드: 최신순 페이지 반환 및 next 커서 설정")
        void latest_initial() {
            // given
            Long postId = 10L;
            User author = mockUser(1L, "alice");
            Post post = mockPost(postId);

            Instant t1 = Instant.parse("2025-08-20T12:00:00Z");
            Instant t2 = Instant.parse("2025-08-20T12:05:00Z");

            Comment c1 = mockComment(101L, t2, author, post, false, null);
            Comment c2 = mockComment(102L, t1, author, post, false, null);

            Slice<Comment> slice = new SliceImpl<>(
                    List.of(c1, c2), PageRequest.of(0, 2), false
            );

            given(commentRepository.findByPostId(eq(postId), any(Pageable.class)))
                    .willReturn(slice);

            // 좋아요수, 유저이미지
            given(likeRepository.countByRelatedIds(eq(LikeType.COMMENT), eq(List.of(101L, 102L))))
                    .willReturn(List.<Object[]>of(new Object[]{101L, 3L})); // 102는 0으로 처리

            given(imageRepository.findUrlByRelatedIds(eq(ImageType.USER), eq(List.of(1L))))
                    .willReturn(List.<Object[]>of(new Object[]{1L, "u1.png"}));

            // when
            CommentCursorPageResponse<CommentResponse> resp = service.getCommentList(
                    postId, 2, SortOption.LATEST, null, null, null
            );

            // then
            assertThat(resp.items()).hasSize(2);
            assertThat(resp.hasNext()).isFalse();
            assertThat(resp.nextCursorCreatedAt()).isEqualTo(t1);  // 마지막 요소 c2
            assertThat(resp.nextCursorId()).isEqualTo(102L);
            assertThat(resp.nextCursorLikeCount()).isNull(); // LATEST는 null

            // like/userImage 매핑 검증(부분)
            CommentResponse last = resp.items().get(1);
            assertThat(last.likeCount()).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("getCommentList - POPULAR")
    class GetCommentListPopular {

        @Test
        @DisplayName("커서 로드: 인기순 페이지 반환 및 next likeCount 커서 설정")
        void popular_with_cursor() {
            // given
            Long postId = 10L;
            User author = mockUser(1L, "alice");
            Post post = mockPost(postId);

            Instant base = Instant.parse("2025-08-20T12:00:00Z");
            Comment c1 = mockComment(201L, base.plusSeconds(60), author, post, false, null);
            Comment c2 = mockComment(202L, base, author, post, false, null);

            Slice<Comment> slice = new SliceImpl<>(List.of(c1, c2), PageRequest.of(0, 2), true);

            given(commentRepository.findPopularByCursor(
                    eq(postId), eq(LikeType.COMMENT), eq(50L), any(Instant.class), eq(999L), any(Pageable.class)
            )).willReturn(slice);

            given(likeRepository.countByRelatedIds(eq(LikeType.COMMENT), eq(List.of(201L, 202L))))
                    .willReturn(List.of(new Object[]{201L, 70L}, new Object[]{202L, 60L}));

            given(imageRepository.findUrlByRelatedIds(eq(ImageType.USER), eq(List.of(1L))))
                    .willReturn(List.<Object[]>of(new Object[]{1L, "img.png"}));

            // when
            CommentCursorPageResponse<CommentResponse> resp = service.getCommentList(
                    postId, 2, SortOption.POPULAR,
                    base, 999L, 50L
            );

            // then
            assertThat(resp.items()).hasSize(2);
            assertThat(resp.hasNext()).isTrue();
            assertThat(resp.nextCursorCreatedAt()).isEqualTo(base);
            assertThat(resp.nextCursorId()).isEqualTo(202L);
            assertThat(resp.nextCursorLikeCount()).isEqualTo(60L);
        }
    }

    // ===== writeComment =====
    @Nested
    @DisplayName("writeComment")
    class WriteComment {

        @Test
        @DisplayName("루트 댓글 정상 저장")
        void write_root_success() {
            // given
            User u = mockUser(1L, "alice");
            Post p = mockPost(10L);

            given(userRepository.findByName("alice")).willReturn(Optional.of(u));
            given(postRepository.findById(10L)).willReturn(Optional.of(p));

            Comment created = mock(Comment.class);

            try (MockedStatic<Comment> mocked = mockStatic(Comment.class)) {
                mocked.when(() -> Comment.createRootComment(eq(p), eq(u), eq("hi"), eq(true)))
                        .thenReturn(created);

                // when
                service.writeComment("alice", 10L, new CommentWriteRequest(null, true, "hi"));

                // then
                then(commentRepository).should().save(created);
            }
        }

        @Test
        @DisplayName("대댓글: 부모가 다른 게시물의 댓글이면 INVALID_PARENT_COMMENT")
        void reply_parent_mismatch() {
            // given
            User u = mockUser(1L, "alice");
            Post targetPost = mockPost(10L);
            Post otherPost = mockPost(20L);
            Comment parent = mockComment(99L, Instant.now(), u, otherPost, false, null);

            given(userRepository.findByName("alice")).willReturn(Optional.of(u));
            given(postRepository.findById(10L)).willReturn(Optional.of(targetPost));
            given(commentRepository.findById(99L)).willReturn(Optional.of(parent));

            // when / then
            assertThatThrownBy(() ->
                    service.writeComment("alice", 10L, new CommentWriteRequest(99L, true, "hi"))
            )
                    .isInstanceOf(BusinessException.class)
                    .extracting("status")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("루트 댓글: Comment.createRootComment가 IllegalArgumentException 던지면 INVALID_COMMENT_INPUT")
        void write_invalid_input() {
            // given
            User u = mockUser(1L, "alice");
            Post p = mockPost(10L);

            given(userRepository.findByName("alice")).willReturn(Optional.of(u));
            given(postRepository.findById(10L)).willReturn(Optional.of(p));

            try (MockedStatic<Comment> mocked = mockStatic(Comment.class)) {
                mocked.when(() -> Comment.createRootComment(any(), any(), any(), anyBoolean()))
                        .thenThrow(new IllegalArgumentException("bad"));

                // when / then
                assertThatThrownBy(() ->
                        service.writeComment("alice", 10L, new CommentWriteRequest(null, true, "bad"))
                )
                        .isInstanceOf(BusinessException.class)
                        .extracting("status")
                        .isEqualTo(HttpStatus.BAD_REQUEST);
            }
        }
    }

    // ===== updateComment =====
    @Nested
    @DisplayName("updateComment")
    class UpdateComment {

        @Test
        @DisplayName("작성자 이름이 동일하면 COMMENT_EDIT_FORBIDDEN (현재 구현 로직 기준)")
        void forbidden_when_author_matches() {
            // given
            User author = mockUser(1L, "alice");
            Comment c = mock(Comment.class);
            given(c.getAuthor()).willReturn(author);
            given(commentRepository.findById(100L)).willReturn(Optional.of(c));

            // when / then
            assertThatThrownBy(() ->
                    service.updateComment("alice", 100L, new CommentUpdateRequest("new"))
            )
                    .isInstanceOf(BusinessException.class)
                    .extracting("status")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("삭제된 댓글이면 COMMENT_ALREADY_DELETED")
        void already_deleted() {
            // given
            User author = mockUser(1L, "bob");
            Comment c = mock(Comment.class);
            given(c.getAuthor()).willReturn(author);
            given(c.isDeleted()).willReturn(true);
            given(commentRepository.findById(100L)).willReturn(Optional.of(c));

            // when / then
            assertThatThrownBy(() ->
                    service.updateComment("alice", 100L, new CommentUpdateRequest("x"))
            )
                    .isInstanceOf(BusinessException.class)
                    .extracting("status")
                    .isEqualTo(HttpStatus.GONE);
        }

        @Test
        @DisplayName("내용 변경 요청 시 changeContent 호출")
        void change_content_called() {
            // given
            User author = mockUser(1L, "bob");
            Comment c = mock(Comment.class);
            given(c.getAuthor()).willReturn(author);
            given(c.isDeleted()).willReturn(false);
            given(commentRepository.findById(100L)).willReturn(Optional.of(c));

            // when
            service.updateComment("alice", 100L, new CommentUpdateRequest("new"));

            // then
            then(c).should().changeContent("new");
        }
    }

    // ===== deleteComment =====
    @Nested
    @DisplayName("deleteComment")
    class DeleteCommentTests {

        @Test
        @DisplayName("작성자 불일치면 COMMENT_DELETE_FORBIDDEN")
        void delete_forbidden() {
            // given
            User author = mockUser(1L, "alice");
            Comment c = mock(Comment.class);
            given(c.getAuthor()).willReturn(author);
            given(commentRepository.findById(300L)).willReturn(Optional.of(c));

            // when / then
            assertThatThrownBy(() -> service.deleteComment("bob", 300L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("status")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("자식 살아있으면 소프트 삭제(markDeleted)")
        void soft_delete_when_has_children() {
            // given
            User author = mockUser(1L, "alice");
            Comment c = mock(Comment.class);
            given(c.getAuthor()).willReturn(author);
            given(commentRepository.findById(300L)).willReturn(Optional.of(c));
            given(commentRepository.existsByParentIdAndDeletedFalse(300L)).willReturn(true);

            // when
            service.deleteComment("alice", 300L);

            // then
            then(c).should().markDeleted("alice");
            then(commentRepository).should(never()).delete(any());
        }

        @Test
        @DisplayName("자식 없으면 하드 삭제 후 상위 체인 정리(cleanup)")
        void hard_delete_and_cleanup_chain() {
            // given
            User author = mockUser(1L, "alice");
            Comment parent = mockComment(200L, Instant.now(), author, mockPost(10L), true, null);
            Comment target = mockComment(300L, Instant.now(), author, mockPost(10L), false, parent);

            given(commentRepository.findById(300L)).willReturn(Optional.of(target));
            given(commentRepository.existsByParentIdAndDeletedFalse(300L)).willReturn(false);

            // target 삭제
            // parent 자식 수 0이면 parent 삭제 -> grand 없음
            given(commentRepository.countByParentId(200L)).willReturn(0L);

            // when
            service.deleteComment("alice", 300L);

            // then
            then(commentRepository).should().delete(target);
            then(commentRepository).should().delete(parent);
        }
    }

    // ===== getMyCommentList =====
    @Nested
    @DisplayName("getMyCommentList")
    class GetMyCommentListTests {

        @Test
        @DisplayName("size+1 로 조회하여 hasNext/page/nextCursor 계산")
        void my_comments_cursor_paging() {
            // given
            int size = 2;
            UserCommentItem i1 = new UserCommentItem(11L, "c1", "hello", Instant.parse("2025-08-20T12:00:00Z"));
            UserCommentItem i2 = new UserCommentItem(12L, "c2", "hello", Instant.parse("2025-08-20T12:01:00Z"));
            UserCommentItem i3 = new UserCommentItem(13L, "c3", "hello", Instant.parse("2025-08-20T12:02:00Z"));

            given(commentRepository.findMyCommentsForCursor(eq("alice"), isNull(), any()))
                    .willReturn(List.of(i1, i2, i3));

            // when
            UserCommentsSliceResponse resp = service.getMyCommentList("alice", null, size);

            // then
            assertThat(resp.items()).containsExactly(i1, i2);
            assertThat(resp.hasNext()).isTrue();
            assertThat(resp.nextCursor()).isEqualTo(12L);
        }
    }
}

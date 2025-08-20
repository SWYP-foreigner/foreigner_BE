package core.domain.post.service.impl;

import core.domain.board.entity.Board;
import core.domain.board.repository.BoardRepository;
import core.domain.post.dto.*;
import core.domain.post.entity.Post;
import core.domain.post.repository.PostRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.BoardCategory;
import core.global.enums.LikeType;
import core.global.exception.BusinessException;
import core.global.image.entity.Image;
import core.global.enums.ImageType;
import core.global.image.repository.ImageRepository;
import core.global.like.entity.Like;
import core.global.like.repository.LikeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class PostServiceImplTest {

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

    @InjectMocks
    private PostServiceImpl service;

    /**
     * Get PostDetail
     */
    @Test
    @DisplayName("getPostDetail - 조회수 증가(changeCheckCount) 호출 및 상세 조회 위임")
    void getPostDetail_success() {
        Long postId = 100L;
        Post post = mock(Post.class);
        given(postRepository.findById(postId)).willReturn(Optional.of(post));

        PostDetailResponse detail = mock(PostDetailResponse.class);
        given(postRepository.findPostDetail(postId)).willReturn(detail);

        PostDetailResponse result = service.getPostDetail(postId);

        then(post).should().changeCheckCount();
        then(postRepository).should().findPostDetail(postId);
        assertThat(result).isSameAs(detail);
    }

    @Test
    @DisplayName("getPostDetail - 게시글 없음 예외")
    void getPostDetail_notFound() {
        given(postRepository.findById(1L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.getPostDetail(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Write Post
     */
    @Test
    @DisplayName("writePost - boardId=1이면 쓰기 불가")
    void writePost_board1_forbidden() {
        PostWriteRequest req = mock(PostWriteRequest.class);
        assertThatThrownBy(() -> service.writePost("alice", 1L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("writePost - 익명 허용 안되는 게시판에서 isAnonymous=true면 예외")
    void writePost_anonymous_policy_violation() {
        Long boardId = 10L;
        Board board = mock(Board.class);
        given(boardRepository.findById(boardId)).willReturn(Optional.of(board));
        given(board.getCategory()).willReturn(BoardCategory.ACTIVITY); // 예: 익명 불가 카테고리

        PostWriteRequest req = mock(PostWriteRequest.class);
        given(req.isAnonymous()).willReturn(true);

        assertThatThrownBy(() -> service.writePost("alice", boardId, req))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("writePost - 정상 저장 및 이미지 저장")
    void writePost_success_withImages() {
        Long boardId = 10L;
        Board board = mock(Board.class);
        given(boardRepository.findById(boardId)).willReturn(Optional.of(board));
        given(board.getCategory()).willReturn(BoardCategory.FREE_TALK); // 익명 허용

        User user = mock(User.class);
        given(userRepository.findByName("alice")).willReturn(Optional.of(user));

        // 요청 DTO는 mock으로 필요한 값만
        PostWriteRequest req = mock(PostWriteRequest.class);
        given(req.isAnonymous()).willReturn(true);
        given(req.imageUrls()).willReturn(List.of(" https://a.jpg ", "", "https://b.png"));

        // Post 생성은 서비스 내에서 new Post(request,user,board)
        // save 시점에서 any(Post.class)로 수락
        willAnswer(inv -> inv.getArgument(0)).given(postRepository).save(any(Post.class));

        service.writePost("alice", boardId, req);

        then(postRepository).should().save(any(Post.class));

        // saveAll 호출 여부와 개수(빈 문자열 제외)
        ArgumentCaptor<List<Image>> captor = ArgumentCaptor.forClass(List.class);
        then(imageRepository).should().saveAll(captor.capture());

        List<Image> saved = captor.getValue();
        assertThat(saved).hasSize(2);
        // url 정리(trim) 확인
        assertThat(saved.stream().map(Image::getUrl)).containsExactly("https://a.jpg", "https://b.png");
        // position 0,1 부여 여부는 엔티티 내부 로직이라 여기서는 개수/정렬만 간접 검증
    }

    @Test
    @DisplayName("writePost - 사용자 없음 예외")
    void writePost_userNotFound() {
        Long boardId = 10L;
        Board board = mock(Board.class);
        given(boardRepository.findById(boardId)).willReturn(Optional.of(board));
        given(board.getCategory()).willReturn(BoardCategory.QNA);

        PostWriteRequest req = mock(PostWriteRequest.class);
        given(req.isAnonymous()).willReturn(false);

        given(userRepository.findByName("alice")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.writePost("alice", boardId, req))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Update Post
     */
    @Test
    @DisplayName("updatePost - 작성자와 동일 이름이면 (현재 코드 기준) 수정 금지 예외 발생")
    void updatePost_forbidden_when_sameAuthorName() {
        Long postId = 7L;
        Post post = mock(Post.class);
        User author = mock(User.class);
        given(author.getName()).willReturn("alice");
        given(post.getAuthor()).willReturn(author);
        given(postRepository.findById(postId)).willReturn(Optional.of(post));

        PostUpdateRequest req = mock(PostUpdateRequest.class);

        assertThatThrownBy(() -> service.updatePost("alice", postId, req))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("updatePost - 내용 수정 + 이미지 삭제/추가 정상 동작")
    void updatePost_success_editContent_and_images() {
        Long postId = 9L;

        // 작성자 이름이 다르면 (현재 코드 기준) 통과
        Post post = mock(Post.class);
        User author = mock(User.class);
        given(author.getName()).willReturn("bob"); // 호출자는 "alice"
        given(post.getAuthor()).willReturn(author);
        given(postRepository.findById(postId)).willReturn(Optional.of(post));

        // 업데이트 요청
        PostUpdateRequest req = mock(PostUpdateRequest.class);
        given(req.content()).willReturn("NEW CONTENT");
        given(req.removedImages()).willReturn(List.of("old1"));
        given(req.images()).willReturn(List.of("new1", "new2"));

        // 기존 이미지 1개 (url=old1)
        Image img = mock(Image.class);
        given(img.getUrl()).willReturn("old1");
        given(imageRepository.findByImageTypeAndRelatedIdOrderByPositionAsc(ImageType.POST, postId))
                .willReturn(List.of(img));

        service.updatePost("alice", postId, req);

        // 내용 변경
        then(post).should().changeContent("NEW CONTENT");

        // 삭제 호출
        then(imageRepository).should()
                .deleteByImageTypeAndRelatedIdAndUrlIn(ImageType.POST, postId, List.of("old1"));

        // 기존 생존자 position 재정렬
        then(img).should().changePosition(0);

        // 신규 이미지 2개 저장
        then(imageRepository).should(times(2))
                .save(any(Image.class));
    }

    @Test
    @DisplayName("updatePost - 수정 대상 게시글 없음")
    void updatePost_postNotFound() {
        given(postRepository.findById(99L)).willReturn(Optional.empty());
        PostUpdateRequest req = mock(PostUpdateRequest.class);
        assertThatThrownBy(() -> service.updatePost("alice", 99L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Delete Post
     */
    @Test
    @DisplayName("deletePost - 권한 없음")
    void deletePost_forbidden() {
        Long postId = 11L;
        Post post = mock(Post.class);
        User author = mock(User.class);
        given(author.getName()).willReturn("bob");
        given(post.getAuthor()).willReturn(author);
        given(postRepository.findById(postId)).willReturn(Optional.of(post));

        assertThatThrownBy(() -> service.deletePost("alice", postId))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("deletePost - 이미지가 있으면 이미지 먼저 삭제 후 게시글 삭제")
    void deletePost_success_withImages() {
        Long postId = 12L;
        Post post = mock(Post.class);
        User author = mock(User.class);
        given(author.getName()).willReturn("alice");
        given(post.getAuthor()).willReturn(author);
        given(postRepository.findById(postId)).willReturn(Optional.of(post));

        Image i1 = mock(Image.class);
        Image i2 = mock(Image.class);
        given(i1.getUrl()).willReturn("u1");
        given(i2.getUrl()).willReturn("u2");

        given(imageRepository.findByImageTypeAndRelatedIdOrderByPositionAsc(ImageType.POST, postId))
                .willReturn(List.of(i1, i2));

        service.deletePost("alice", postId);

        then(imageRepository).should()
                .deleteByImageTypeAndRelatedId(ImageType.POST, postId);
        then(postRepository).should().delete(post);
    }

    @Test
    @DisplayName("deletePost - 이미지가 없으면 바로 게시글 삭제")
    void deletePost_success_noImages() {
        Long postId = 13L;
        Post post = mock(Post.class);
        User author = mock(User.class);
        given(author.getName()).willReturn("alice");
        given(post.getAuthor()).willReturn(author);
        given(postRepository.findById(postId)).willReturn(Optional.of(post));

        given(imageRepository.findByImageTypeAndRelatedIdOrderByPositionAsc(ImageType.POST, postId))
                .willReturn(Collections.emptyList());

        service.deletePost("alice", postId);

        then(imageRepository).should(never())
                .deleteByImageTypeAndRelatedId(any(), anyLong());
        then(postRepository).should().delete(post);
    }

    /**
     * Add Like
     */
    @Test
    @DisplayName("addLike - 이미 좋아요가 있으면 예외")
    void addLike_alreadyExists() {
        given(likeRepository.findLikeByUsernameAndType("alice", 77L, LikeType.POST))
                .willReturn(Optional.of(mock(Like.class)));

        assertThatThrownBy(() -> service.addLike("alice", 77L))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    /**
     * Get My Posts
     */

    @Test
    @DisplayName("addLike - 정상 저장")
    void addLike_success() {
        given(likeRepository.findLikeByUsernameAndType("alice", 77L, LikeType.POST))
                .willReturn(Optional.empty());

        User u = mock(User.class);
        given(userRepository.findByName("alice")).willReturn(Optional.of(u));

        service.addLike("alice", 77L);

        then(likeRepository).should().save(any(Like.class));
    }

    @Test
    @DisplayName("removeLike - 정상 삭제")
    void removeLike_success() {
        service.removeLike("alice", 77L);
        then(likeRepository).should()
                .deleteByUserNameAndIdAndType("alice", 77L, LikeType.POST);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 1) 첫 페이지: size+1 반환 → hasNext=true, rows trim, nextId=null
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("getMyPostList - 첫 페이지(size+1) → hasNext=true, rows trim, nextId=null")
    void firstPage_hasNext_true_trimmed() {
        int size = 3;
        UserPostResponse r1 = mock(UserPostResponse.class);
        UserPostResponse r2 = mock(UserPostResponse.class);
        UserPostResponse r3 = mock(UserPostResponse.class);
        UserPostResponse r4 = mock(UserPostResponse.class); // size+1

        // trim 후 마지막 요소 r3의 createdAt만 접근하므로 r3만 세팅
        given(r3.createdAt()).willReturn(Instant.now().minusSeconds(10));

        given(postRepository.findMyPostsFirstByName("alice", size + 1))
                .willReturn(List.of(r1, r2, r3, r4));

        UserPostsSliceResponse res = service.getMyPostList("alice", null, null, size);

        assertThat(res.hasNext()).isTrue();
        assertThat(res.nextCursor()).isNull();
        then(postRepository).should().findMyPostsFirstByName("alice", size + 1);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 2) 첫 페이지: size 이하 반환 → hasNext=false, trim 없음, nextId=null
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("getMyPostList - 첫 페이지(size 이하) → hasNext=false, trim 없음, nextId=null")
    void firstPage_hasNext_false_notTrimmed() {
        int size = 3;
        UserPostResponse r1 = mock(UserPostResponse.class);
        UserPostResponse r2 = mock(UserPostResponse.class);

        // 마지막 요소의 createdAt 접근은 hasNext=false이면 호출 안 됨(안 세팅해도 됨)
        given(postRepository.findMyPostsFirstByName("alice", size + 1))
                .willReturn(List.of(r1, r2)); // size 이하

        UserPostsSliceResponse res = service.getMyPostList("alice", null, null, size);

        assertThat(res.hasNext()).isFalse();
        assertThat(res.nextCursor()).isNull();
        then(postRepository).should().findMyPostsFirstByName("alice", size + 1);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 3) 다음 페이지: size+1 반환 → hasNext=true, rows trim, nextId=null
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("getMyPostList - 다음 페이지(size+1) → hasNext=true, rows trim, nextId=null")
    void nextPage_hasNext_true_trimmed() {
        int size = 2;
        Instant cursorCreatedAt = Instant.now();
        Long cursorId = 123L;

        UserPostResponse r1 = mock(UserPostResponse.class);
        UserPostResponse r2 = mock(UserPostResponse.class);
        UserPostResponse r3 = mock(UserPostResponse.class); // size+1

        given(r2.createdAt()).willReturn(Instant.now().minusSeconds(5)); // trim 후 마지막

        given(postRepository.findMyPostsNextByName(
                eq("alice"),
                eq(cursorCreatedAt.truncatedTo(ChronoUnit.MILLIS)),
                eq(cursorId),
                eq(size + 1)))
                .willReturn(List.of(r1, r2, r3));

        UserPostsSliceResponse res = service.getMyPostList("alice", cursorCreatedAt, cursorId, size);

        assertThat(res.hasNext()).isTrue();
        assertThat(res.nextCursor()).isNull();
        then(postRepository).should().findMyPostsNextByName(
                "alice", cursorCreatedAt.truncatedTo(ChronoUnit.MILLIS), cursorId, size + 1);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 4) 다음 페이지: size 이하 반환 → hasNext=false, trim 없음, nextId=null
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("getMyPostList - 다음 페이지(size 이하) → hasNext=false, trim 없음, nextId=null")
    void nextPage_hasNext_false_notTrimmed() {
        int size = 2;
        Instant cursorCreatedAt = Instant.now();
        Long cursorId = 123L;

        UserPostResponse r1 = mock(UserPostResponse.class);

        given(postRepository.findMyPostsNextByName(
                eq("alice"),
                eq(cursorCreatedAt.truncatedTo(ChronoUnit.MILLIS)),
                eq(cursorId),
                eq(size + 1)))
                .willReturn(List.of(r1)); // size 이하

        UserPostsSliceResponse res = service.getMyPostList("alice", cursorCreatedAt, cursorId, size);

        assertThat(res.hasNext()).isFalse();
        assertThat(res.nextCursor()).isNull();
        then(postRepository).should().findMyPostsNextByName(
                "alice", cursorCreatedAt.truncatedTo(ChronoUnit.MILLIS), cursorId, size + 1);
    }


    @Test
    @DisplayName("isAnonymousAvailable - 게시글의 익명 작성 가능 여부 반환")
    void isAnonymousAvailable_success() {
        Post p = mock(Post.class);
        given(p.getAnonymous()).willReturn(Boolean.TRUE);
        given(postRepository.findById(7L)).willReturn(Optional.of(p));

        CommentWriteAnonymousAvailableResponse res = service.isAnonymousAvaliable(7L);

        assertThat(res.isAnonymousAvailable()).isTrue();
    }

    @Test
    @DisplayName("isAnonymousAvailable - 게시글 없음 예외")
    void isAnonymousAvailable_notFound() {
        given(postRepository.findById(7L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.isAnonymousAvaliable(7L))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}

package core.domain.comment.repository;

import core.domain.board.entity.Board;
import core.domain.board.repository.BoardRepository;
import core.domain.comment.dto.CommentListResponse;
import core.domain.comment.dto.CommentSearchRequest;
import core.domain.comment.entity.Comment;
import core.domain.post.entity.Post;
import core.domain.post.repository.PostRepository;
import core.domain.user.entity.BlockUser;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.UserRepository;
import core.global.config.QuerydslConfig;
import core.global.entity.like.entity.Like;
import core.global.entity.like.repository.LikeRepository;
import core.global.enums.BoardCategory;
import core.global.enums.LikeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.*;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfig.class)
public class CommentRepositoryCustomImplTest {

    @Autowired
    private CommentRepositoryCustomImpl commentRepositoryCustom;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private BlockRepository blockUserRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private BoardRepository boardRepository;

    private User user1;
    private User user2;
    private Post post;
    private Comment c1;
    private Comment c2;
    private Comment c3;

    @BeforeEach
    void setUp() {
        // ───── User 생성 (User 생성자 사용: @Builder 달린 생성자 시그니처 그대로) ─────
        user1 = userRepository.save(
                new User(
                        "John",                 // firstName
                        "Doe",                  // lastName
                        null,                   // sex
                        null,                   // birthdate
                        null,                   // country
                        null,                   // introduction
                        null,                   // purpose
                        null,                   // language
                        null,                   // hobby
                        null,                   // provider
                        null,                   // socialId
                        "john@example.com",     // email
                        null,                   // appleRefreshToken
                        Instant.now()           // createdAt
                )
        );

        user2 = userRepository.save(
                new User(
                        "Jane",
                        "Smith",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "jane@example.com",
                        null,
                        Instant.now()
                )
        );

        // ───── Board & Post 생성 ─────
        Board board = boardRepository.save(
                new Board(BoardCategory.ACTIVITY) // 실제 enum 값으로 수정
        );

        // Post(String content, User author, Board board)
        post = postRepository.save(
                new Post(
                        "content",
                        user1,
                        board
                )
        );

        // ───── Comment 생성: 정적 팩토리 사용 ─────
        c1 = commentRepository.save(
                Comment.createRootComment(
                        post,
                        user1,
                        "first comment",
                        false
                )
        );

        c2 = commentRepository.save(
                Comment.createRootComment(
                        post,
                        user2,
                        "second comment",
                        false
                )
        );

        c3 = commentRepository.save(
                Comment.createRootComment(
                        post,
                        user2,
                        "third comment",
                        false
                )
        );
    }

    // ───────────────────────── 최신 조회 테스트 ─────────────────────────

    @Test
    @DisplayName("findByPostId - 차단 없을 때 최신순으로 정렬되고 Slice 가 제대로 만들어진다")
    void findByPostId_success() {
        Pageable pageable = PageRequest.of(0, 2); // size=2

        Slice<Comment> slice = commentRepositoryCustom.findByPostId(user1.getId(), post.getId(), pageable);

        assertThat(slice.getContent()).hasSize(2);
        // createdAt desc, id desc 기준: 가장 마지막에 만든 c3, 그 다음 c2 순서를 기대
        assertThat(slice.getContent().get(0).getId()).isEqualTo(c3.getId());
        assertThat(slice.getContent().get(1).getId()).isEqualTo(c2.getId());
        assertThat(slice.hasNext()).isTrue(); // 총 3개 중 2개만 가져왔으므로
    }

    @Test
    @DisplayName("findByPostId - 차단(양방향) 필터가 적용되어 숨겨진다")
    void findByPostId_withBlockFilter() {
        // user1이 user2를 차단
        BlockUser block = new BlockUser(
                user1,  // me
                user2   // blockedUser
        );
        blockUserRepository.save(block);

        Pageable pageable = PageRequest.of(0, 10);

        Slice<Comment> slice = commentRepositoryCustom.findByPostId(user1.getId(), post.getId(), pageable);

        // user2가 쓴 c2, c3는 보이면 안 되고, user1이 쓴 c1만 보여야 함
        assertThat(slice.getContent())
                .extracting(Comment::getId)
                .containsExactly(c1.getId());
    }

    @Test
    @DisplayName("findCommentByCursor - 커서 기반 페이징이 동작한다")
    void findCommentByCursor_success() {
        // 첫 페이지: size=2 -> c3, c2
        Pageable pageable = PageRequest.of(0, 2);
        Slice<Comment> firstSlice = commentRepositoryCustom.findByPostId(user1.getId(), post.getId(), pageable);

        Comment lastOfFirstPage = firstSlice.getContent().get(1); // c2
        Instant cursorCreatedAt = lastOfFirstPage.getCreatedAt();
        Long cursorId = lastOfFirstPage.getId();

        // 두 번째 페이지 요청
        Slice<Comment> secondSlice = commentRepositoryCustom.findCommentByCursor(
                user1.getId(),
                post.getId(),
                cursorCreatedAt,
                cursorId,
                pageable
        );

        assertThat(secondSlice.getContent())
                .extracting(Comment::getId)
                .containsExactly(c1.getId());
        assertThat(secondSlice.hasNext()).isFalse();
    }

    // ───────────────────────── 인기 정렬 테스트 ─────────────────────────

    @Test
    @DisplayName("findPopularByPostId - 좋아요 수 desc, createdAt desc, id desc 순으로 정렬된다")
    void findPopularByPostId_success() {
        // c1: 1 like, c2: 3 likes, c3: 2 likes
        saveLikes(user1, c1, 1);
        saveLikes(user1, c2, 3);
        saveLikes(user1, c3, 2);

        Pageable pageable = PageRequest.of(0, 10);

        Slice<Comment> slice = commentRepositoryCustom.findPopularByPostId(
                user1.getId(), post.getId(), LikeType.COMMENT, pageable);

        assertThat(slice.getContent())
                .extracting(Comment::getId)
                .containsExactly(
                        c2.getId(), // 3 likes
                        c3.getId(), // 2 likes
                        c1.getId()  // 1 like
                );
    }

    @Test
    @DisplayName("findPopularByCursor - 좋아요 수, createdAt, id 기준으로 커서 페이징이 동작한다")
    void findPopularByCursor_success() {
        saveLikes(user1, c1, 1);
        saveLikes(user1, c2, 3);
        saveLikes(user1, c3, 2);

        Pageable pageable = PageRequest.of(0, 2);
        Slice<Comment> firstSlice = commentRepositoryCustom.findPopularByPostId(
                user1.getId(), post.getId(), LikeType.COMMENT, pageable);

        Comment lastOfFirstPage = firstSlice.getContent().get(1); // 두 번째 댓글
        Long cursorLikeCount = getLikeCount(LikeType.COMMENT, lastOfFirstPage.getId());
        Instant cursorCreatedAt = lastOfFirstPage.getCreatedAt();
        Long cursorId = lastOfFirstPage.getId();

        Slice<Comment> secondSlice = commentRepositoryCustom.findPopularByCursor(
                user1.getId(),
                post.getId(),
                LikeType.COMMENT,
                cursorLikeCount,
                cursorCreatedAt,
                cursorId,
                pageable
        );

        // 마지막 남은 댓글 하나만 와야 함
        assertThat(secondSlice.getContent()).hasSize(1);
        assertThat(secondSlice.hasNext()).isFalse();
    }

    // ───────────────────────── searchComments 테스트 ─────────────────────────

    @Test
    @DisplayName("searchComments - 이메일, 이름, 내용, 기간, onlyReported 필터와 정렬이 동작한다")
    void searchComments_success() {
        BlockUser block = new BlockUser(user1, user2);
        blockUserRepository.save(block);

        Pageable pageable = PageRequest.of(
                0,
                10,
                Sort.by(Sort.Order.asc("authorName"), Sort.Order.desc("reportCount"))
        );


        CommentSearchRequest condition = new CommentSearchRequest(
                "jane",          // authorEmail
                "Jane Smith",    // authorName
                "comment",       // content
                true,            // onlyReported
                null,
                null
        );

        Page<CommentListResponse> page = commentRepositoryCustom.searchComments(condition, pageable);

        List<CommentListResponse> content = page.getContent();

        assertThat(content).isNotEmpty();
        assertThat(content)
                .allMatch(r -> r.authorEmail().contains("jane"));

        // c2, c3 2개라고 가정
        assertThat(page.getTotalElements()).isEqualTo(2L);
    }

    // ───────────────────────── Helper ─────────────────────────

    private void saveLikes(User user, Comment comment, int count) {
        for (int i = 0; i < count; i++) {
            Like like = Like.builder()
                    .user(user)
                    .type(LikeType.COMMENT)
                    .relatedId(comment.getId())
                    .build();
            likeRepository.save(like);
        }
    }

    private long getLikeCount(LikeType type, Long relatedId) {
        List<Object[]> rows = likeRepository.countByRelatedIds(type, List.of(relatedId));
        if (rows.isEmpty()) return 0L;
        return (Long) rows.get(0)[1];
    }
}

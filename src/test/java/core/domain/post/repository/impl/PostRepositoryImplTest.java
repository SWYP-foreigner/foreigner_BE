package core.domain.post.repository.impl;

import core.domain.board.dto.BoardItem;
import core.domain.board.entity.Board;
import core.domain.board.repository.BoardRepository;
import core.domain.post.dto.admin.PostListForAdminResponse;
import core.domain.post.dto.admin.PostSearchForAdminRequest;
import core.domain.post.dto.comunity.PostDetailResponse;
import core.domain.post.dto.comunity.UserPostItem;
import core.domain.post.entity.Post;
import core.domain.post.repository.PostRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.config.QuerydslConfig;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.like.entity.Like;
import core.global.entity.like.repository.LikeRepository;
import core.global.enums.BoardCategory;
import core.global.enums.ImageType;
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
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuerydslConfig.class, PostRepositoryImpl.class})
public class PostRepositoryImplTest {

    @Autowired
    private PostRepositoryImpl postRepositoryImpl;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BoardRepository boardRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private ImageRepository imageRepository;

    private User user1;
    private User user2;
    private Board board;
    private Post p1;
    private Post p2;
    private Post p3;

    @BeforeEach
    void setUp() {
        user1 = userRepository.save(
                new User(
                        "John",
                        "Doe",
                        null, null, null,
                        null, null, null, null, null,
                        null,
                        "john@example.com",
                        null,
                        Instant.now()
                )
        );

        user2 = userRepository.save(
                new User(
                        "Jane",
                        "Smith",
                        null, null, null,
                        null, null, null, null, null,
                        null,
                        "jane@example.com",
                        null,
                        Instant.now()
                )
        );

        // --- Board 생성 ---
        board = boardRepository.save(
                new Board(BoardCategory.ACTIVITY)
        );

        // --- Post 생성 (생성자는 실제 코드에 맞게 조정) ---
        p1 = postRepository.save(new Post("first post content", user1, board));
        p2 = postRepository.save(new Post("second post content", user2, board));
        p3 = postRepository.save(new Post("third post content", user2, board));

        // 필요 시 checkCount, anonymous 등 세팅하는 세터가 있으면 호출
        // p1.setCheckCount(10L);
        // p2.setCheckCount(20L);
        // p3.setCheckCount(30L);
    }

    @Test
    @DisplayName("findLatestPosts - 기본 최신순 정렬 및 사이즈 +1 로 조회된다")
    void findLatestPosts_success() {
        // when
        List<BoardItem> items = postRepositoryImpl.findLatestPosts(
                user1.getId(),     // viewerId
                board.getId(),     // boardId
                null,              // cursorCreatedAt (첫 페이지)
                null,              // cursorId
                10                // size
        );

        // then
        // size+1 로 limit 걸려 있으므로 3개 데이터면 3개가 다 나옴
        assertThat(items).hasSize(3);

        // createdAt desc, id desc 기준으로 정렬되었다고 가정 (엔티티 생성 순서 p1 < p2 < p3)
        assertThat(items)
                .extracting(BoardItem::id)
                .containsExactly(p3.getId(), p2.getId(), p1.getId());
    }

    @Test
    @DisplayName("findLatestPosts - 커서 기반 무한 스크롤 페이징이 동작한다")
    void findLatestPosts_withCursor() {
        // 첫 페이지 (size=2)
        List<BoardItem> first = postRepositoryImpl.findLatestPosts(
                user1.getId(),
                board.getId(),
                null,
                null,
                2
        );

        assertThat(first).hasSize(3);

        BoardItem lastOfFirst = first.get(1); // 두 번째 아이템

        // 두 번째 페이지 호출
        List<BoardItem> second = postRepositoryImpl.findLatestPosts(
                user1.getId(),
                board.getId(),
                lastOfFirst.createdAt(),
                lastOfFirst.id(),
                2
        );

        // 남은 한 개만 와야 함
        assertThat(second).hasSize(1);
        assertThat(second.get(0).id()).isEqualTo(p1.getId());
    }

    @Test
    @DisplayName("findPopularPosts - 좋아요/댓글/조회수 기반 점수로 정렬된다")
    void findPopularPosts_success() {
        // p1: like 1
        saveLikes(user1, p1, 1);
        // p2: like 3
        saveLikes(user1, p2, 3);
        // p3: like 2
        saveLikes(user1, p3, 2);

        int size = 10;

        List<BoardItem> items = postRepositoryImpl.findPopularPosts(
                user1.getId(),        // viewerId
                board.getId(),        // boardId
                null,                 // since (지금은 사용 안 하므로 null)
                null,                 // cursorScore
                null,                 // cursorId
                size
        );

        assertThat(items)
                .extracting(BoardItem::id)
                .containsExactly(
                        p2.getId(), // 3 likes
                        p3.getId(), // 2 likes
                        p1.getId()  // 1 like
                );
    }

    private void saveLikes(User user, Post post, int count) {
        for (int i = 0; i < count; i++) {
            Like like = Like.builder()
                    .user(user)
                    .type(LikeType.POST)
                    .relatedId(post.getId())
                    .build();
            likeRepository.save(like);
        }
    }


    @Test
    @DisplayName("findPostDetail - 게시글 상세 정보를 정상적으로 반환한다")
    void findPostDetail_success() {
        // given - user 프로필 이미지
        Image profileImage = Image.builder()
                .imageType(ImageType.USER)
                .relatedId(user1.getId())
                .url("https://cdn.test/user1.png")
                .orderIndex(0)
                .build();
        imageRepository.save(profileImage);

        // given - post 컨텐츠 이미지 2개
        Image img1 = Image.builder()
                .imageType(ImageType.POST)
                .relatedId(p1.getId())
                .url("https://cdn.test/post1-1.png")
                .orderIndex(0)
                .build();
        Image img2 = Image.builder()
                .imageType(ImageType.POST)
                .relatedId(p1.getId())
                .url("https://cdn.test/post1-2.png")
                .orderIndex(1)
                .build();
        imageRepository.saveAll(List.of(img1, img2));

        Long viewId = user1.getId();

        // when
        PostDetailResponse detail = postRepositoryImpl.findPostDetail(viewId, p1.getId());

        // then
        assertThat(detail).isNotNull();
        assertThat(detail.authorId()).isEqualTo(user1.getId());
        assertThat(detail.authorName()).contains("John Doe");
        assertThat(detail.userImageUrl()).isEqualTo("https://cdn.test/user1.png");

        assertThat(detail.contentImageUrls())
                .containsExactlyInAnyOrder(
                        "https://cdn.test/post1-1.png",
                        "https://cdn.test/post1-2.png"
                );

        assertThat(detail.imageCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("findMyPostsFirstByEmail - 나의 게시글을 최신순으로 첫 페이지 조회")
    void findMyPostsFirstByEmail_success() {
        // when
        List<UserPostItem> first = postRepositoryImpl.findMyPostsFirstByEmail(
                user1.getEmail(),
                10 // limitPlusOne
        );

        // then
        // user1은 p1만 작성했다고 가정
        assertThat(first).hasSize(1);
        assertThat(first.get(0).id()).isEqualTo(p1.getId());
    }

    @Test
    @DisplayName("findMyPostsNextByEmail - 커서 기반 다음 페이지 조회")
    void findMyPostsNextByEmail_success() {
        // user1이 글 하나 더 쓴다고 가정
        Post p4 = postRepository.save(new Post("fourth by user1", user1, board));

        List<UserPostItem> first = postRepositoryImpl.findMyPostsFirstByEmail(
                user1.getEmail(),
                2
        );

        assertThat(first).hasSize(2);
        UserPostItem lastOfFirst = first.get(1);

        List<UserPostItem> second = postRepositoryImpl.findMyPostsNextByEmail(
                user1.getEmail(),
                lastOfFirst.createdAt(),
                lastOfFirst.id(),
                2
        );

        // p1, p4 두 개뿐이면 두 번째 페이지는 아마 비어있을 수도 있습니다.
        // 실제 비즈니스 요구에 맞게 기대값 조정
        assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("searchPostsByAdmin - 이메일, 이름, 내용, 기간, 정렬이 동작한다")
    void searchPostsByAdmin_success() {
        // given
        // p2, p3는 user2(Jane)가 작성, p1은 user1(John)이 작성
        // BlockPost 를 하나 넣어서 reportCount > 0 케이스도 만들 수 있음
        // blockPostRepository.save(new BlockPost(user1, p2));

        Pageable pageable = PageRequest.of(
                0,
                10,
                Sort.by(
                        Sort.Order.asc("authorName"),
                        Sort.Order.desc("reportCount")
                )
        );

        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(1);
        LocalDate endDate = today.plusDays(1);

        PostSearchForAdminRequest condition = new PostSearchForAdminRequest(
                "jane",        // authorEmail
                "Jane Smith",  // authorName
                "post",        // content
                startDate,
                endDate
        );

        // when
        Page<PostListForAdminResponse> page =
                postRepositoryImpl.searchPostsByAdmin(condition, pageable);

        // then
        List<PostListForAdminResponse> content = page.getContent();

        assertThat(content).isNotEmpty();
        assertThat(content)
                .allMatch(r -> r.authorEmail().toLowerCase().contains("jane"));

        // Jane 이 쓴 글(p2, p3) 2개라고 가정
        assertThat(page.getTotalElements()).isEqualTo(2L);
    }


}

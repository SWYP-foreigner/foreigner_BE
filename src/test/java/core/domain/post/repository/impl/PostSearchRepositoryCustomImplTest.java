package core.domain.post.repository.impl;

import core.domain.board.entity.Board;
import core.domain.board.repository.BoardRepository;
import core.domain.post.dto.search.PostSearchRequest;
import core.domain.post.dto.search.SearchResultView;
import core.domain.post.dto.comunity.PostWriteRequest;
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
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuerydslConfig.class, PostSearchRepositoryCustomImpl.class})
class PostSearchRepositoryCustomImplTest {

    @Autowired private PostSearchRepositoryCustomImpl repo;

    @Autowired private UserRepository userRepository;
    @Autowired private BoardRepository boardRepository;
    @Autowired private PostRepository postRepository;
    @Autowired private LikeRepository likeRepository;
    @Autowired private ImageRepository imageRepository;

    private User viewer;
    private User author1;
    private User author2;
    private Board board;

    private Post p1;
    private Post p2;
    private Post p3Anonymous;

    @BeforeEach
    void setUp() {
        viewer = userRepository.save(new User(
                "View", "Er",
                null, null, null, null, null, null, null, null, null,
                "viewer@example.com", null, Instant.now()
        ));

        author1 = userRepository.save(new User(
                "John", "Doe",
                null, null, null, null, null, null, null, null, null,
                "john@example.com", null, Instant.now()
        ));

        author2 = userRepository.save(new User(
                "Jane", "Smith",
                null, null, null, null, null, null, null, null, null,
                "jane@example.com", null, Instant.now()
        ));

        board = boardRepository.save(new Board(BoardCategory.ACTIVITY));

        // content에 "korea" 포함
        p1 = postRepository.save(new Post("korea trip is awesome. seoul food", author1, board));
        p2 = postRepository.save(new Post("korea study. language and culture", author2, board));

        PostWriteRequest anonReq = new PostWriteRequest(
                "korea secret anonymous content",
                true,
                List.of("https://cdn.test/p1-1.png")
        );
        p3Anonymous = postRepository.save(new Post(anonReq, author2, board));

        // author1 프로필 이미지
        imageRepository.save(Image.builder()
                .imageType(ImageType.USER)
                .relatedId(author1.getId())
                .url("https://cdn.test/u1.png")
                .orderIndex(0)
                .build());

        // p1 본문 이미지 2개
        imageRepository.saveAll(List.of(
                Image.builder().imageType(ImageType.POST).relatedId(p1.getId()).url("https://cdn.test/p1-1.png").orderIndex(0).build(),
                Image.builder().imageType(ImageType.POST).relatedId(p1.getId()).url("https://cdn.test/p1-2.png").orderIndex(1).build()
        ));

        // viewer가 p1에 좋아요
        likeRepository.save(Like.builder()
                .user(viewer)
                .type(LikeType.POST)
                .relatedId(p1.getId())
                .build());
    }

    @Test
    @DisplayName("search - 기본 검색 결과가 나오고 likedByMe/이미지/작성자 정보가 채워진다")
    void search_success_basicProjection() {
        List<SearchResultView> result = repo.search(new PostSearchRequest(
                "korea",
                viewer.getId(),
                board.getId(),
                List.of(),
                null, null, null, 10
        ));

        assertThat(result).isNotEmpty();

        var first = result.get(0);
        var item = first.item();

        assertThat(item.contentPreview()).isNotBlank();
        assertThat(item.postId()).isNotNull();
        assertThat(item.createdAt()).isNotNull();

        // likedByMe 검증 (p1에 viewer가 like)
        assertThat(result)
                .anyMatch(r -> r.item().postId().equals(p1.getId()) && Boolean.TRUE.equals(r.item().isLiked()));

        // p1: 본문 이미지 2개 -> imageCount 2, thumbnail url 존재
        var p1View = result.stream()
                .filter(r -> r.item().postId().equals(p1.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(p1View.item().imageCount()).isEqualTo(2);
        assertThat(p1View.item().contentImageUrl()).isNotBlank();

        // p1 작성자 프로필 이미지 URL
        assertThat(p1View.item().userImageUrl()).isEqualTo("https://cdn.test/u1.png");
    }

    @Test
    @DisplayName("search - 익명 글은 authorId/userImageUrl이 null로 내려온다(익명 보호)")
    void search_anonymous_masking() {
        List<SearchResultView> result = repo.search(new PostSearchRequest(
                "korea",
                viewer.getId(),
                board.getId(),
                List.of(),
                null, null, null, 10
        ));

        var anon = result.stream()
                .filter(r -> r.item().postId().equals(p3Anonymous.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(anon.item().isAnonymous()).isTrue();
        assertThat(anon.item().authorId()).isNull();
        assertThat(anon.item().userImageUrl()).isNull();
        assertThat(anon.item().authorName()).isEqualTo("Anonymity");
    }

    @Test
    @DisplayName("search - blockedIds가 적용되어 차단된 작성자의 글은 나오지 않는다")
    void search_blockedIds_filter() {
        List<SearchResultView> result = repo.search(new PostSearchRequest(
                "korea",
                viewer.getId(),
                board.getId(),
                List.of(author2.getId()),
                null, null, null, 10
        ));

        // author2가 쓴 p2는 제외 기대
        assertThat(result)
                .noneMatch(r -> r.item().postId().equals(p2.getId()));

        // 익명 글은 authorId가 null이므로 통과 가능
        assertThat(result)
                .allMatch(r -> r.item().authorId() == null || !r.item().authorId().equals(author2.getId()));
    }

    @Test
    @DisplayName("search - 커서(afterTime/afterId)로 다음 페이지에서 중복 재등장 방지")
    void search_cursor_paging() {
        List<SearchResultView> first = repo.search(new PostSearchRequest(
                "korea",
                viewer.getId(),
                board.getId(),
                List.of(),
                null, null, null, 2
        ));

        assertThat(first).isNotEmpty();

        var last = first.get(first.size() - 1);
        Double afterScore = last.score();
        Instant afterTime = last.item().createdAt();
        Long afterId = last.item().postId();

        List<SearchResultView> second = repo.search(new PostSearchRequest(
                "korea",
                viewer.getId(),
                board.getId(),
                List.of(),
                afterScore, afterTime, afterId, 10
        ));

        assertThat(second)
                .noneMatch(r -> r.item().postId().equals(afterId));
    }

    @Test
    @DisplayName("suggest - prefix 자동완성 결과가 나오고 중복 스니펫이 제거된다")
    void suggest_success_dedup() {
        List<String> suggestions = repo.suggest(
                "kor",
                board.getId(),
                List.of(),
                10
        );

        assertThat(suggestions).isNotNull();
        assertThat(suggestions).isNotEmpty();

        long distinct = suggestions.stream().distinct().count();
        assertThat(distinct).isEqualTo(suggestions.size());
    }

    @Test
    @DisplayName("findHotKeywordsOrTitles - topN 제한이 적용된다")
    void findHotKeywordsOrTitles_topN() {
        List<String> hot = repo.findHotKeywordsOrTitles(5);

        assertThat(hot).isNotNull();
        assertThat(hot.size()).isLessThanOrEqualTo(5);
        assertThat(hot).allMatch(s -> s.equals(s.toLowerCase()));
    }
}

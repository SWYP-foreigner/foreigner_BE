//package core.domain.post.integration;
//
//import com.google.firebase.FirebaseApp;
//import com.google.firebase.messaging.FirebaseMessaging;
//import core.domain.board.entity.Board;
//import core.domain.board.repository.BoardRepository;
//import core.domain.post.entity.Post;
//import core.domain.post.repository.PostRepository;
//import core.domain.user.entity.User;
//import core.domain.user.repository.UserRepository;
//import core.global.entity.image.repository.ImageRepository;
//import core.global.entity.like.repository.LikeRepository;
//import core.global.enums.BoardCategory;
//import io.micrometer.core.instrument.MeterRegistry;
//import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
//import jakarta.transaction.Transactional;
//import org.hamcrest.Matchers;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Test;
//import org.mockito.Mockito;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.boot.test.context.TestConfiguration;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Import;
//import org.springframework.http.MediaType;
//import org.springframework.test.context.ActiveProfiles;
//import org.springframework.test.context.jdbc.Sql;
//import org.springframework.test.web.servlet.MockMvc;
//
//import java.time.Instant;
//
//import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
//
//@SpringBootTest
//@AutoConfigureMockMvc
//@ActiveProfiles("test") // test DB 사용
//@Transactional
//@Import(PostSearchIntegrationTest.DummyInfraConfig.class)
//@Sql(
//        scripts = {"/db/migration/V4__pgroonga_baseline.sql",
//                "/db/migration/V5__pgroonga_indexes_concurrent.sql"},
//        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS
//)
//class PostSearchIntegrationTest {
//
//    @Autowired
//    private MockMvc mockMvc;
//
//    @Autowired
//    private UserRepository userRepository;
//
//    @Autowired
//    private PostRepository postRepository;
//
//    @Autowired
//    private BoardRepository boardRepository;
//
//    @Autowired
//    private ImageRepository imageRepository;
//
//    @Autowired
//    private LikeRepository likeRepository;
//
//    @TestConfiguration
//    static class MetricsTestConfig {
//
//        @Bean
//        MeterRegistry meterRegistry() {
//            // 테스트용으로 가벼운 in-memory 레지스트리
//            return new SimpleMeterRegistry();
//        }
//    }
//
//    @TestConfiguration
//    static class DummyInfraConfig {
//
//        @Bean
//        public FirebaseApp firebaseApp() {
//            // 실제 credentials 안 읽고, 그냥 목 객체 리턴
//            return Mockito.mock(FirebaseApp.class);
//        }
//
//        @Bean
//        public FirebaseMessaging firebaseMessaging() {
//            return Mockito.mock(FirebaseMessaging.class);
//        }
//    }
//
//
//
//    private User user1;
//    private User user2;
//    private Board board;
//    private Post p1, p2, p3;
//
//    @BeforeEach
//    void setUp() {
//        user1 = userRepository.save(
//                new User(
//                        "Victory",                              // firstName
//                        "Omoghene",                             // lastName
//                        "Female",                               // sex
//                        "07/06/2006",                           // birthDate (엔티티 타입에 맞게 필요하면 수정)
//                        "Nigeria",                              // country
//                        "I'm a lady with passion for learning and",
//                        "Marriage",                             // purpose
//                        "EN",                                   // language
//                        "Traveling,Beauty,Music,Dancing,K-Drama Lover",
//                        "GOOGLE",                               // provider
//                        "109366747121168476783",                // socialId
//                        "victoryomoghene548@gmail.com",         // email
//                        null,                                   // appleRefreshToken
//                        Instant.now()                           // createdAt
//                )
//        );
//
//        user2 = userRepository.save(
//                new User(
//                        "Juliana",
//                        "Ju",
//                        "Female",
//                        "07/04/1999",
//                        "Brazil",
//                        "Brasileira ❤",
//                        "Marriage",
//                        "PT-BR",
//                        "Music,Gaming,Exploring Cafes,Beauty,Shopping",
//                        "GOOGLE",
//                        "102790361424333509963",
//                        "eitajuu3@gmail.com",
//                        null,
//                        Instant.now()
//                )
//        );
//
//        board = boardRepository.save(new Board(BoardCategory.ACTIVITY));
//
//        p1 = postRepository.save(new Post("hello apple content", user1, board));
//        p2 = postRepository.save(new Post("apple events news", user2, board));
//        p3 = postRepository.save(new Post("banana republic", user2, board));
//
//        // PGroonga 확장 및 인덱스는 Flyway(V4, V5)로 이미 생성된 상태라고 가정
//    }
//
//    @Test
//    @DisplayName("🔎 search API — PGroonga 검색 / 정렬 / 페이징 동작 확인")
//    void search_success() throws Exception {
//
//        mockMvc.perform(
//                        get("/api/v1/search/{boardId}/posts", board.getId())
//                                .param("q", "apple")
//                                .param("size", "10")
//                                .contentType(MediaType.APPLICATION_JSON)
//                )
//                .andExpect(status().isOk())
//                // ApiResponse<CursorPageResponse<SearchResultView>>
//                .andExpect(jsonPath("$.data.items").isArray())
//                .andExpect(jsonPath("$.data.items.length()").value(2))
//                // SearchResultView 안에 BoardItem 이 있고, 그 안의 contentPreview 검사
//                .andExpect(jsonPath("$.data.items[0].boardItem.contentPreview",
//                        Matchers.containsString("apple")));
//    }
//
//    @Test
//    @DisplayName("🟩 suggest API — prefix matching OK & unique 정렬")
//    void suggest_success() throws Exception {
//
//        mockMvc.perform(
//                        get("/api/v1/search/{boardId}/suggest", board.getId())
//                                .param("q", "ap")
//                                .contentType(MediaType.APPLICATION_JSON)
//                )
//                .andExpect(status().isOk())
//                // 컨트롤러가 List<String> 을 그대로 반환하므로 루트가 배열
//                .andExpect(jsonPath("$").isArray())
//                .andExpect(jsonPath("$[0]", Matchers.containsString("apple")));
//    }
//
//    @Test
//    @DisplayName("📝 최근 검색어 API — 배열 반환 확인")
//    void recent_success() throws Exception {
//
//        mockMvc.perform(
//                        get("/api/v1/search/recent")
//                                .contentType(MediaType.APPLICATION_JSON)
//                )
//                .andExpect(status().isOk())
//                // RecentSearchRedisService.list() → List<String>
//                .andExpect(jsonPath("$").isArray());
//    }
//
//
//}

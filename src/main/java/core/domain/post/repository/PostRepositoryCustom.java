package core.domain.post.repository;

import core.domain.board.dto.BoardItem;
import core.domain.post.dto.admin.PostListForAdminResponse;
import core.domain.post.dto.admin.PostSearchForAdminRequest;
import core.domain.post.dto.comunity.PostDetailResponse;
import core.domain.post.dto.comunity.UserPostItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

public interface PostRepositoryCustom {
    List<BoardItem> findLatestPosts(Long userId, Long boardId, Instant cursorCreatedAt, Long cursorId, int size);

    List<BoardItem> findPopularPosts(Long userId, Long boardId, Instant since, Long cursorScore, Long cursorId, int size);

    PostDetailResponse findPostDetail(Long userId, Long postId);

    List<UserPostItem> findMyPostsFirstByEmail(String email, int limitPlusOne);

    List<UserPostItem> findMyPostsNextByEmail(String email, Instant cursorCreatedAt, Long cursorId, int limitPlusOne);

    Page<PostListForAdminResponse> searchPostsByAdmin(PostSearchForAdminRequest condition, Pageable pageable);
}

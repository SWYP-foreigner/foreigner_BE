package core.domain.post.repository;

import core.domain.board.dto.BoardItem;
import core.domain.post.dto.UserPostItem;
import core.domain.post.dto.PostDetailResponse;

import java.time.Instant;
import java.util.List;

public interface PostRepositoryCustom {
        List<BoardItem> findLatestPosts(Long userId, Long boardId,
                                        Instant cursorCreatedAt,
                                        Long cursorId,
                                        int size,
                                        String q);

        List<BoardItem> findPopularPosts(Long userId, Long boardId, Instant since, Long cursorScore, Long cursorId, int size, String q);

        PostDetailResponse findPostDetail(Long postId);

        List<UserPostItem> findMyPostsFirstByEmail(String email, int limitPlusOne);

        List<UserPostItem> findMyPostsNextByEmail(String email, Instant cursorCreatedAt, Long cursorId, int limitPlusOne);

}

package core.domain.post.repository;

import core.domain.board.dto.BoardItem;
import core.domain.post.dto.UserPostItem;
import core.domain.post.dto.PostDetailResponse;

import java.time.Instant;
import java.util.List;

public interface PostRepositoryCustom {
        List<BoardItem> findLatestPosts(Long boardId,
                                        Instant cursorCreatedAt,
                                        Long cursorId,
                                        int size,
                                        String q);

        List<BoardItem> findPopularPosts(Long boardId, Instant since, Long cursorScore, Long cursorId, int size, String q);

        PostDetailResponse findPostDetail(Long postId);

        List<UserPostItem> findMyPostsFirstByName(String name, int limitPlusOne);

        List<UserPostItem> findMyPostsNextByName(String name, Instant cursorCreatedAt, Long cursorId, int limitPlusOne);

}

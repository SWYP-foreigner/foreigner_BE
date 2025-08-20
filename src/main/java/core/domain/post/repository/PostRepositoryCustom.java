package core.domain.post.repository;

import core.domain.board.dto.BoardResponse;
import core.domain.post.dto.UserPostResponse;
import core.domain.post.dto.PostDetailResponse;

import java.time.Instant;
import java.util.List;

public interface PostRepositoryCustom {
        List<BoardResponse> findLatestPosts(Long boardId,
                                            Instant cursorCreatedAt,
                                            Long cursorId,
                                            int size,
                                            String q);

        List<BoardResponse> findPopularPosts(Long boardId, Instant since, Long cursorScore, Long cursorId, int size, String q);

        PostDetailResponse findPostDetail(Long postId);

        List<UserPostResponse> findMyPostsFirstByName(String name, int limitPlusOne);

        List<UserPostResponse> findMyPostsNextByName(String name, Instant cursorCreatedAt, Long cursorId, int limitPlusOne);

}

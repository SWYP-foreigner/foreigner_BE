package core.domain.post.service;

import core.domain.board.dto.BoardResponse;
import core.global.enums.BoardCategory;
import core.global.enums.SortOption;

import java.time.Instant;
import java.util.List;

public interface PostService {

    List<BoardResponse> getPostList(Long boardCategory, SortOption sort, Instant cursorCreatedAt, Long cursorId, int size);

    PostDetailResponse getPostDetail(Long boardId, Long postId);

    void addLike(String username, Long boardId, Long postId);

    void writePost(String name, PostWriteRequest boardCategory);

    PostWriteAnonymousAvailableResponse isAnonymousAvaliable(Long boardId);

    void updatePost(String name, @Positive Long boardId, @Valid PostUpdateRequest updateRequest);

    void deletePost(String name, @Positive Long postId);

}

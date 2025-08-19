package core.domain.post.service;

import core.domain.board.dto.BoardCursorPageResponse;
import core.domain.board.dto.BoardResponse;
import core.domain.post.dto.PostUpdateRequest;
import core.domain.post.dto.PostWriteAnonymousAvailableResponse;
import core.domain.post.dto.PostDetailResponse;
import core.domain.post.dto.PostWriteRequest;
import core.global.enums.SortOption;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

public interface PostService {

    BoardCursorPageResponse<BoardResponse> getPostList(Long boardId, SortOption sort, Instant cursorCreatedAt, Long cursorId, Long cursorScore, int size);

    PostDetailResponse getPostDetail(Long postId);

    void addLike(String username, Long boardId);

    void writePost(String name, @Positive Long boardId, PostWriteRequest request);

    void updatePost(String name, @Positive Long boardId, @Valid PostUpdateRequest updateRequest);

    void deletePost(String name, @Positive Long postId);

    void removeLike(String name, @Positive Long postId);

    PostDetailResponse getMyPostList(@Positive Long postId);

    CommentWriteAnonymousAvailableResponse isAnonymousAvaliable(@Positive(message = "postId는 양수여야 합니다.") Long postId);
}

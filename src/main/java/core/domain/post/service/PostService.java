package core.domain.post.service;

import core.domain.board.dto.BoardItem;
import core.domain.post.dto.*;
import core.global.enums.SortOption;
import core.global.pagination.CursorPageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

public interface PostService {

    CursorPageResponse<BoardItem> getPostList(Long boardId, SortOption sort, String cursor, int size);

    PostDetailResponse getPostDetail(Long postId);

    void addLike(String username, Long boardId);

    void writePost(String name, @Positive Long boardId, PostWriteRequest request);

    void updatePost(String name, @Positive Long boardId, @Valid PostUpdateRequest updateRequest);

    void deletePost(String name, @Positive Long postId);

    void removeLike(String name, @Positive Long postId);

    CursorPageResponse<UserPostItem> getMyPostList(String name, String cursor, int size);

    CommentWriteAnonymousAvailableResponse isAnonymousAvaliable(@Positive(message = "postId는 양수여야 합니다.") Long postId);
}

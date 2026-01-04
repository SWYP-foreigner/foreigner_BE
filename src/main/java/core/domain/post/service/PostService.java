package core.domain.post.service;

import core.domain.board.dto.BoardItem;
import core.domain.post.dto.admin.PostReportRequest;
import core.domain.user.entity.User;
import core.domain.post.dto.comunity.*;
import core.global.enums.CommunitySortOption;
import core.global.pagination.CursorPageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface PostService {

    CursorPageResponse<BoardItem> getPostList(Long boardId, CommunitySortOption sort, String cursor, int size);

    PostDetailResponse getPostDetail(Long postId, Boolean translate);

    void addLike(Long boardId);

    void writePost(@Positive Long boardId, PostWriteRequest request);

    void writePostForChat(Long roomId, PostWriteForChatRequest request);

    void updatePost(@Positive Long boardId, @Valid PostUpdateRequest updateRequest);

    void deletePost(@Positive Long postId);

    void removeLike(@Positive Long postId);

    CursorPageResponse<UserPostItem> getMyPostList(String cursor, int size);

    CommentWriteAnonymousAvailableResponse isAnonymousAvaliable(@Positive(message = "postId는 양수여야 합니다.") Long postId);

    void blockUser(@Positive Long postId);

    void blockPost(@Positive Long postId);

    void createAdminPost(String title, String content, String publishType,
                         String boardCategoryStr,
                         List<MultipartFile> generalImages,
                         MultipartFile mainThumbnailFile, MultipartFile popularThumbnailFile,
                         List<MultipartFile> contentImages,
                         User adminUser) throws IOException;

    void reportPost(Long reporterUserId, Long postId, PostReportRequest request);

}

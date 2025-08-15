package core.domain.post.service.impl;

import core.domain.board.dto.BoardResponse;
import core.domain.board.entity.Board;
import core.domain.board.repository.BoardRepository;
import core.domain.post.dto.PostUpdateRequest;
import core.domain.post.dto.PostDetailResponse;
import core.domain.post.dto.PostWriteAnonymousAvailableResponse;
import core.domain.post.dto.PostWriteRequest;
import core.domain.post.entity.Post;
import core.domain.post.repository.PostRepository;
import core.domain.post.service.PostService;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.BoardCategory;
import core.global.enums.ErrorCode;
import core.global.enums.LikeType;
import core.global.enums.SortOption;
import core.global.exception.BusinessException;
import core.global.image.entity.Image;
import core.global.image.entity.ImageType;
import core.global.image.repository.ImageRepository;
import core.global.like.entity.Like;
import core.global.like.repository.LikeRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    private final PostRepository postRepository;
    private final BoardRepository boardRepository;
    private final LikeRepository likeRepository;
    private final UserRepository userRepository;
    private final ImageRepository imageRepository;

    @Override
    @Transactional(readOnly = true)
    public List<BoardResponse> getPostList(Long boardId, SortOption sort, Instant cursorCreatedAt, Long cursorId, int size) {
        final Long resolvedBoardId =
                (boardId == null || boardId <= 0L) ? null : boardId;

        if (resolvedBoardId != null && !boardRepository.existsById(resolvedBoardId)) {
            throw new BusinessException(ErrorCode.BOARD_NOT_FOUND);
        }

        List<BoardResponse> rows;
        switch (sort) {
            case LATEST ->
                    rows = postRepository.findLatestPosts(resolvedBoardId, cursorCreatedAt, cursorId, size, null);
            case POPULAR ->
                    rows = postRepository.findPopularPosts(resolvedBoardId, Instant.now().minus(Duration.ofDays(7)), null, cursorId, size, null); // TODO
            default -> rows = postRepository.findLatestPosts(resolvedBoardId, cursorCreatedAt, cursorId, size, null);
        }

        if (rows.size() > size) {
            rows = rows.subList(0, size);
        }
        return rows;
    }

    @Override
    @Transactional
    public PostDetailResponse getPostDetail(Long boardId, Long postId) {
        Boolean isTrue = boardRepository.isMatchedPost(postId, boardId);
        if (!isTrue) {
            throw new BusinessException(ErrorCode.BOARD_AND_POST_NOT_MATCHED);
        }

        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BOARD_NOT_FOUND));
        addViews(post);

        return postRepository.findPostDetail(postId);
    }

}

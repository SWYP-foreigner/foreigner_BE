package core.domain.post.service.impl;

import core.domain.board.dto.BoardResponse;
import core.domain.board.repository.BoardRepository;
import core.domain.post.repository.PostRepository;
import core.domain.post.service.PostService;
import core.global.enums.SortOption;
import core.global.enums.BoardCategory;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {
    private final PostRepository postRepository;
    private final BoardRepository boardRepository;

    @Override
    public List<BoardResponse> getPostList( BoardCategory boardCategory, SortOption sort, Instant cursorCreatedAt, Long cursorId, int size) {
        final Long boardId = (boardCategory == BoardCategory.ALL)
                ? null
                : boardRepository.findIdByCategory(boardCategory)
                .orElseThrow(() -> new BusinessException(ErrorCode.BOARD_NOT_FOUND)).getId();

        List<BoardResponse> rows;
        switch (sort) {
            case LATEST -> rows = postRepository.findLatestPosts(boardId, cursorCreatedAt, cursorId, size, null);
            case POPULAR   -> rows = postRepository.findPopularPosts(boardId, Instant.now().minus(Duration.ofDays(7)), null, cursorId, size, null); // TODO
            default        -> rows = postRepository.findLatestPosts(boardId, cursorCreatedAt, cursorId, size, null);
        }

        // size+1 방어 → 필요 시 컨트롤러에서 SlicePayload로 감싸세요
        if (rows.size() > size) {
            rows = rows.subList(0, size);
        }
        return rows;
    }
}

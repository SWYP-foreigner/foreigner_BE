package core.domain.post.service;

import core.domain.board.dto.BoardResponse;
import core.global.enums.BoardCategory;
import core.global.enums.SortOption;

import java.time.Instant;
import java.util.List;

public interface PostService {

    List<BoardResponse> getPostList(BoardCategory boardCategory, SortOption sort, Instant cursorCreatedAt, Long cursorId, int size);
}

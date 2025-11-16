package core.domain.post.dto.search;

import core.domain.board.dto.BoardItem;

public record SearchResultView(BoardItem item, double score) {}
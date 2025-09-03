package core.global.search;

import core.domain.board.dto.BoardItem;

public record SearchResultView(BoardItem item, String highlight, double score) {}
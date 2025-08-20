package core.domain.board.dto;

import core.global.enums.BoardCategory;

public record CategoryListResponse(
        BoardCategory category
) {
}

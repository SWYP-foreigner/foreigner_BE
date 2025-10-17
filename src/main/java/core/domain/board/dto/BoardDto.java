package core.domain.board.dto;

import core.domain.board.entity.Board;

public record BoardDto(
        Long id,
        String categoryName
) {
    public static BoardDto from(Board board) {
        return new BoardDto(board.getId(), board.getCategory().name());
    }
}
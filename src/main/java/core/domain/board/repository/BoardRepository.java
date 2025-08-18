package core.domain.board.repository;

import core.domain.board.entity.Board;
import core.global.enums.BoardCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;


public interface BoardRepository extends JpaRepository<Board, Long> {
    Optional<Board> findIdByCategory(BoardCategory boardCategory);

    Boolean existsByCategory(BoardCategory category);

}

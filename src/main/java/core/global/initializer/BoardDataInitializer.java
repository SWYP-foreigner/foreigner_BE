package core.global.initializer;

import core.domain.board.entity.Board;
import core.domain.board.repository.BoardRepository;
import core.global.enums.BoardCategory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile({"dev","local"})
@RequiredArgsConstructor
public class BoardDataInitializer implements CommandLineRunner {

    private final BoardRepository boardRepository;

    @Override
    public void run(String... args) {
        for (BoardCategory category : BoardCategory.values()) {
            try {
                boardRepository.save(new Board(category));
            } catch (DataIntegrityViolationException e) {
                // 다른 인스턴스가 먼저 넣은 케이스: 정보 로그로만 처리
                log.info("Category {} already inserted by another instance.", category);
            }
        }
    }
}


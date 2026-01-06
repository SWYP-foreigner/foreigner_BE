package core.domain.poll.repository;

import core.domain.poll.entity.Poll;
import core.global.enums.PollType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PollRepository extends JpaRepository<Poll, Long> {
    Optional<Poll> findFirstByTypeOrderByCreatedAtDesc(PollType type);
}

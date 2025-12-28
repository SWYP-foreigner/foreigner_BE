package core.domain.maincontent.repository;

import core.domain.maincontent.entity.Poll;
import core.domain.maincontent.entity.PollType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PollRepository extends JpaRepository<Poll, Long> {
    Optional<Poll> findFirstByTypeOrderByCreatedAtDesc(PollType type);
}

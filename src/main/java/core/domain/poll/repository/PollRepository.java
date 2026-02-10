package core.domain.poll.repository;

import core.domain.poll.entity.Poll;
import core.global.enums.PollType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PollRepository extends JpaRepository<Poll, Long> {
    @Query("SELECT p FROM Poll p JOIN FETCH p.options WHERE p.type = :type ORDER BY p.createdAt DESC LIMIT 1")
    Optional<Poll> findLatestPollByType(@Param("type") PollType type);
}

package core.domain.poll.repository;

import core.domain.poll.entity.PollOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PollOptionRepository extends JpaRepository<PollOption, Long> {
    List<PollOption> findAllByPollIdIn(List<Long> pollPostIds);

    @Query("select op.id from PollOption op where op.poll.id = :pollId and op.isCorrect=true")
    Optional<Long> findCorrectOptionId(@Param("pollId") Long id);
}

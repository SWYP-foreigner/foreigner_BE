package core.domain.poll.repository;

import core.domain.poll.entity.PollOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PollOptionRepository extends JpaRepository<PollOption, Long> {
    List<PollOption> findAllByPollIdIn(List<Long> pollPostIds);
}

package core.domain.poll.repository;

import core.domain.poll.entity.Poll;
import core.domain.poll.entity.VoteRecord;
import core.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VoteRecordRepository extends JpaRepository<VoteRecord, Long> {
    Optional<VoteRecord> findByUserIdAndPollId(Long userId, Long pollId);
    boolean existsByUserAndPoll(User user, Poll poll);
}

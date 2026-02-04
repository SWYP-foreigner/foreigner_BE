package core.domain.poll.repository;

import core.domain.poll.entity.Poll;
import core.domain.poll.entity.VoteRecord;
import core.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VoteRecordRepository extends JpaRepository<VoteRecord, Long> {
    Optional<VoteRecord> findByUserIdAndPollId(Long userId, Long pollId);
    boolean existsByUserAndPoll(User user, Poll poll);

    @Query("SELECT v.poll.id, v.pollOption.id FROM VoteRecord v WHERE v.user.id = :userId AND v.poll.id IN :pollIds")
    List<Object[]> findUserVotesInPolls(@Param("userId") Long userId, @Param("pollIds") List<Long> pollIds);

    boolean existsByPollId(Long pollId);
}

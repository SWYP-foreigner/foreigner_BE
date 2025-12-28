package core.domain.maincontent.repository;

import core.domain.maincontent.entity.Poll;
import core.domain.maincontent.entity.VoteRecord;
import core.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoteRecordRepository extends JpaRepository<VoteRecord, Long> {

    boolean existsByUserAndPoll(User user, Poll poll);
}

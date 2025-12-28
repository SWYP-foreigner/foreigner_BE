package core.domain.mainpage.repository;

import core.domain.mainpage.entity.Poll;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PollRepository extends JpaRepository<Long, Poll> {

}

package core.global.userfeedback;

import core.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
public interface UserFeedbackRepository extends JpaRepository<UserFeedback, Long> {
    boolean existsByUser(User user);
}
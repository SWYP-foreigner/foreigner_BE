package core.global.userfeedback;

import core.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserFeedbackRepository extends JpaRepository<UserFeedback, Long> {


    @Modifying
    @Query("delete from UserFeedback uf where uf.user.id = :userId")
    void deleteAllByUserIdExplicit(@Param("userId") Long userId);
}
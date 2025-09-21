package core.domain.user.repository;

import core.domain.user.entity.BlockUser;
import core.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BlockRepository extends JpaRepository<BlockUser, Long> {

    @Query("select b.blocked from BlockUser b " +
           "where b.user.email = :email")
    List<User> getBlockUsersByUserEmail(@Param("email") String email);

    @Query("select count(b) > 0 from BlockUser b " +
           "where b.user.id = :myId and b.blocked.id = :counterId")
    boolean existsBlock(@Param("myId") Long myId, @Param("counterId") Long counterId);

    @Query("SELECT b FROM BlockUser b WHERE b.user = :user AND b.blocked = :blockedUser")
    Optional<BlockUser> findBlockRelationship(@Param("user") User user, @Param("blockedUser") User blockedUser);

    List<BlockUser> findByUser(User user);
    @Query("select count(b) > 0 from BlockUser b " +
            "where b.user.email = :email and b.blocked.email = :email")
    boolean existsBlockedByEmail(@Param("email") String  email, @Param("email") String authorEmail);

}

package core.domain.user.repository;

import core.domain.user.entity.BlockUser;
import core.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BlockRepository extends JpaRepository<BlockUser, Long> {

    @Query("select b.blocked from BlockUser b " +
           "where b.user.email = :email")
    List<User> getBlockUsersByUserEmail(@Param("email") String email);

    @Query("select count(b) > 0 from BlockUser b " +
           "where b.user.id = :myId and b.blocked.id = :counterId")
    boolean existsBlock(@Param("myId") Long myId, @Param("counterId") Long counterId);

    @Query("select count(b) > 0 from BlockUser b " +
           "where b.user.email = :email or b.blocked.email = :email")
    boolean existsBlockedByEmail(@Param("email") String  email);

}

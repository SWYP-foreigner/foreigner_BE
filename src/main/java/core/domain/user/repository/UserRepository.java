package core.domain.user.repository;


import core.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User>findBySocialId(String socialId);
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);   // ✅ 필수
    long countByIdIn(Set<Long> allParticipantIds);
}

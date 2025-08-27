package core.domain.user.repository;


import core.domain.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User>findBySocialId(String socialId);

    Optional<User> findByEmail(String email);

    Optional<User> findByName(String name);

    long countByIdIn(Set<Long> allParticipantIds);

    @Query("SELECT u FROM User u WHERE u.firstName = :firstName AND u.lastName = :lastName")
    Optional<User> findByFirstAndLastName(@Param("firstName") String firstName,
                                          @Param("lastName") String lastName);

    @Query("SELECT u FROM User u WHERE u.id != :meId")
    Page<User> findCandidatesExcluding(Long meId, Pageable pageable);
    Optional<User> getUserById(Long id);


    List<User> findByFirstNameIgnoreCaseAndIdNot(String firstName, Long excludeId);
    List<User> findByLastNameIgnoreCaseAndIdNot(String lastName, Long excludeId);
    List<User> findByFirstNameIgnoreCaseAndLastNameIgnoreCaseAndIdNot(String firstName, String lastName, Long excludeId);


}

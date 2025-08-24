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
   List<User> findByFirstAndLastNameList(@Param("firstName") String firstName,
                                          @Param("lastName") String lastName);

    @Query("SELECT u FROM User u WHERE u.firstName = :firstName AND u.lastName = :lastName")
    Optional<User> findByFirstAndLastName(@Param("firstName") String firstName,
                                          @Param("lastName") String lastName);

    List<User> findByFirstName(String firstName);
    List<User> findByLastName(String lastName);


    /**
     * 테스트용이 아닌 실제 서비스에서는 본인을 제외하고 (로그인이 된 사용자는 검색X)
     * 검색이 되게 만듬
     * @param firstName
     * @param excludeId
     * @return
     */
    List<User> findByFirstNameIgnoreCaseAndIdNot(String firstName, Long excludeId);
    List<User> findByLastNameIgnoreCaseAndIdNot(String lastName, Long excludeId);
    List<User> findByFirstNameIgnoreCaseAndLastNameIgnoreCaseAndIdNot(String firstName, String lastName, Long excludeId);

    @Query("SELECT u FROM User u WHERE u.id <> :meId")
    Page<User> findCandidatesExcluding(@Param("meId") Long meId, Pageable pageable);
}

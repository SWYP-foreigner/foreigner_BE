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
    Optional<User> findByProviderAndSocialId(String provider, String socialId);

    Optional<User> findByEmail(String email);

    Optional<User> findByName(String name);

    long countByIdIn(Set<Long> allParticipantIds);

    @Query("SELECT u FROM User u WHERE u.firstName = :firstName AND u.lastName = :lastName")
    Optional<User> findByFirstAndLastName(@Param("firstName") String firstName,
                                          @Param("lastName") String lastName);

    @Query("SELECT u FROM User u WHERE u.id != :meId")
    Page<User> findCandidatesExcluding(Long meId, Pageable pageable);
    Optional<User> getUserById(Long id);

    @Query("SELECT u FROM User u WHERE u.id != :meId AND u.purpose IS NOT NULL AND u.country IS NOT NULL AND u.language IS NOT NULL AND u.hobby IS NOT NULL")
    Page<User> findPageNullMemberAndMember(@Param("meId") Long meId, Pageable pageable);

    /**
     * QueryDSL 수정이 필요할듯
     */

//    @Query("SELECT u FROM User u " +
//            "JOIN Follow f ON (f.user.id = u.id AND f.following.id = :currentUserId) " +
//            "OR (f.following.id = u.id AND f.user.id = :currentUserId) " +
//            "WHERE f.status = 'ACCEPTED' AND " +
//            "(:firstName IS NULL OR LOWER(u.firstName) LIKE CONCAT('%', LOWER(:firstName), '%')) AND " +
//            "(:lastName IS NULL OR LOWER(u.lastName) LIKE CONCAT('%', LOWER(:lastName), '%'))")
//    List<User> findAcceptedFriendsByFirstAndLastName(
//            @Param("currentUserId") Long currentUserId,
//            @Param("firstName") String firstName,
//            @Param("lastName") String lastName
//    );

    boolean existsByEmail(String email);

}

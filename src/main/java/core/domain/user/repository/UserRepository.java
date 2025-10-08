package core.domain.user.repository;


import core.domain.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByProviderAndSocialId(String provider, String socialId);

    Optional<User> findByEmail(String email);

    Optional<User> findByName(String name);

    boolean existsByEmail(String email);

    @Query("SELECT u FROM User u WHERE u.id NOT IN :excludeIds " +
            "AND u.purpose IS NOT NULL AND u.purpose <> '' " +
            "AND u.country IS NOT NULL AND u.country <> '' " +
            "AND u.birthdate IS NOT NULL AND u.birthdate <> '' " +
            "AND u.language IS NOT NULL AND u.language <> ''")
    List<User> findFullProfiledRecommendationCandidates(@Param("excludeIds") Collection<Long> excludeIds);

}

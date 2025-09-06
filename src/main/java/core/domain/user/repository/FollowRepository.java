package core.domain.user.repository;


import core.domain.user.entity.Follow;
import core.domain.user.entity.User;
import core.global.enums.FollowStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;


@Repository
public interface FollowRepository extends JpaRepository<Follow,Long> {
    // 특정 사용자가 다른 사용자에게 보낸 팔로우 요청(PENDING 상태)이 있는지 확인
    Optional<Follow> findByUserAndFollowingAndStatus(User user, User following, FollowStatus status);

    /**
     기존 메서드: 내가 팔로우하는 사람들을 조회 (보낸사람 조회)
     */
    @Query("SELECT f FROM Follow f JOIN FETCH f.following WHERE f.user = :user AND f.status = :status")
    List<Follow> findByUserAndStatus(@Param("user") User user, @Param("status") FollowStatus status);

    /*
        새로운 메서드: 나를 팔로우하는 사람들을 조회 (받은 사람 조회)
     */
    @Query("SELECT f FROM Follow f JOIN FETCH f.user WHERE f.following = :user AND f.status = :status")
    List<Follow> findByFollowingAndStatus(@Param("user") User user, @Param("status") FollowStatus status);



    // 특정 사용자와 팔로우 대상의 관계를 조회 (상태 무관)
    Optional<Follow> findByUserAndFollowing(User user, User following);

    // Correctly finds a Follow entity by the IDs of the 'user' and 'following' objects
    Optional<Follow> findByUser_IdAndFollowing_IdAndStatus(Long userId, Long followingId, FollowStatus status);
    /**
     * 특정 사용자와 관련된 모든 팔로우 관계를 삭제합니다.
     * (해당 사용자가 팔로우한 경우 + 해당 사용자를 팔로우한 경우 모두)
     * @param userId 삭제할 사용자의 ID
     */
    @Modifying
    @Query("DELETE FROM Follow f WHERE f.user.id = :userId OR f.following.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);

    @Query("SELECT f FROM Follow f " +
            "WHERE (f.user.id = :userId OR f.following.id = :userId) " +
            "AND f.status = :status")
    List<Follow> findAllAcceptedFollowsByUserId(@Param("userId") Long userId,
                                                @Param("status") FollowStatus status);
}
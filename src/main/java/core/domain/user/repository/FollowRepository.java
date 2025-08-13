package core.domain.user.repository;


import core.domain.user.entity.Follow;
import core.domain.user.entity.User;
import core.global.enums.FollowStatus;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * 특정 사용자가 팔로우하는 모든 목록을 상태(status)와 함께 조회합니다.
     * 팔로우 대상(f.following)의 정보를 즉시 로딩하기 위해 FETCH JOIN을 사용합니다.
     */
    @Query("SELECT f FROM Follow f JOIN FETCH f.following WHERE f.user = :user AND f.status = :status")
    List<Follow> findByUserAndStatus(@Param("user") User user, @Param("status") FollowStatus status);


    /**
     * 특정 사용자에게 들어온 모든 팔로우 요청(PENDING 상태)을 조회합니다.
     * 요청을 보낸 사용자(f.user)의 정보를 즉시 로딩하기 위해 FETCH JOIN을 사용합니다.
     */
    @Query("SELECT f FROM Follow f JOIN FETCH f.user WHERE f.following = :following AND f.status = :status")
    List<Follow> findByFollowingAndStatus(@Param("following") User following, @Param("status") FollowStatus status);


    // 특정 사용자와 팔로우 대상의 관계를 조회 (상태 무관)
    Optional<Follow> findByUserAndFollowing(User user, User following);

    // Correctly finds a Follow entity by the IDs of the 'user' and 'following' objects
    Optional<Follow> findByUser_IdAndFollowing_IdAndStatus(Long userId, Long followingId, FollowStatus status);
}
package core.domain.user.repository;


import core.domain.user.entity.Follow;
import core.domain.user.entity.User;
import core.global.enums.FollowStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FollowRepository extends JpaRepository<Follow,Long> {
    // 특정 사용자가 다른 사용자에게 보낸 팔로우 요청(PENDING 상태)이 있는지 확인
    Optional<Follow> findByUserAndFollowingAndStatus(User user, User following, FollowStatus status);

    // 특정 사용자가 팔로우하는 모든 목록을 상태(status)와 함께 조회
    List<Follow> findByUserAndStatus(User user, FollowStatus status);


    // 특정 사용자에게 들어온 모든 팔로우 요청(PENDING 상태)을 조회
    // 수정 후 (정상 작동)
    List<Follow> findByFollowingAndStatus(User following, FollowStatus status);


    // 특정 사용자와 팔로우 대상의 관계를 조회 (상태 무관)
    Optional<Follow> findByUserAndFollowing(User user, User following);

    // Correctly finds a Follow entity by the IDs of the 'user' and 'following' objects
    Optional<Follow> findByUser_IdAndFollowing_IdAndStatus(Long userId, Long followingId, FollowStatus status);
}
package core.domain.userdevicetoken.repository;

import core.domain.user.entity.User;
import core.domain.userdevicetoken.entity.UserDeviceToken;
import core.global.enums.NotificationType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserDeviceTokenRepository extends JpaRepository<UserDeviceToken, Long> {

    List<UserDeviceToken> findByDeviceToken(String deviceToken);
    void deleteAllByUserId(Long userId);
    List<UserDeviceToken> findAllByUser(User user);

    /**
     * 🚀 [최적화 쿼리]
     * 1. Target: 'UserDeviceToken' 테이블 기준
     * 2. Join: User(국가, 활동시간 확인용) + UserNotificationSetting(알림 수신 동의 확인용)
     * 3. Filter: 한국인(KR) + 최근 7일 활동 + 알림 설정 ON
     * 4. Select: 엔티티가 아닌 'deviceToken(String)'만 조회 (메모리 절약)
     */
    @Query("SELECT udt.deviceToken " +
            "FROM UserDeviceToken udt " +
            "JOIN udt.user u " +
            "JOIN u.notificationSettings uns " +
            "WHERE u.country IN :targetCountries " +  // 1. 타겟 국가 (한국)
            "AND u.lastSeenAt >= :activeSince " +     // 2. 최근 활동 유저
            "AND uns.notificationType = :type " +     // 3. 알림 타입 (NEW_USER)
            "AND uns.enabled = true " +               // 4. 알림 켜둔 사람만
            "AND udt.deviceToken IS NOT NULL")
    Slice<String> findTokensForBroadcast(
            @Param("targetCountries") List<String> targetCountries,
            @Param("activeSince") Instant activeSince,
            @Param("type") NotificationType type,
            Pageable pageable
    );

    /**
     * [New] 여러 유저의 토큰을 한 번에 조회 (IN 절 최적화)
     * udt.user.id를 사용하여 User 객체 조인 없이 ID만으로 비교합니다.
     */
    @Query("SELECT udt FROM UserDeviceToken udt WHERE udt.user.id IN :userIds")
    List<UserDeviceToken> findAllByUserIdIn(@Param("userIds") List<Long> userIds);

    /**
     * [New] 만료된 토큰 리스트를 한 번에 삭제 (Bulk Delete)
     * 방어 로직에서 수집된 유효하지 않은 토큰들을 일괄 삭제할 때 사용합니다.
     */
    @Modifying
    @Query("DELETE FROM UserDeviceToken udt WHERE udt.deviceToken IN :deviceTokens")
    void deleteByDeviceTokenIn(@Param("deviceTokens") List<String> deviceTokens);
}
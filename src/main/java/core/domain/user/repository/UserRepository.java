package core.domain.user.repository;


import core.domain.user.dto.StringCountDto;
import core.domain.user.entity.User;
import core.global.enums.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, UserRepositoryCustom {
    Optional<User> findByProviderAndSocialId(String provider, String socialId);

    Optional<User> findByEmail(String email);
    List<User> findAllByEmail(String email);
    Optional<User> getUserById(Long id);

    List<User> findByUserRoleAndCreatedAtAfter(Role userRole, Instant createdAt);
    List<User> findByUserRole(Role userRole);
    boolean existsByEmail(String email);

    @Query("SELECT u FROM User u " +
            "WHERE u.id NOT IN :excludeIds " +
            // [핵심] 최근 활동 시간 제한 (이게 있어야 답장률이 올라갑니다)
            "AND u.lastSeenAt >= :limitTime " +

            // [프로필 완성 조건] (빈 문자열이나 null이 아닌 경우)
            "AND u.country IS NOT NULL AND u.country <> '' " +
            "AND u.birthdate IS NOT NULL AND u.birthdate <> '' " +
            "AND u.language IS NOT NULL AND u.language <> '' " +

            // [엔티티 기준 추가 보강] User 엔티티의 updateRoleBasedOnProfile 로직과 일치시킴
            "AND u.introduction IS NOT NULL AND u.introduction <> '' " +
            "AND u.hobby IS NOT NULL AND u.hobby <> '' " +
            "AND u.sex IS NOT NULL AND u.sex <> '' " +

            // (선택) 확실하게 하려면 Role까지 체크
            "AND u.userRole = 'USER'")
    List<User> findActiveCandidates(
            @Param("excludeIds") Collection<Long> excludeIds,
            @Param("limitTime") Instant limitTime
    );
    @Query(value = """
            SELECT
              SUM(CASE WHEN last_seen_at IS NOT NULL AND last_seen_at >= NOW() - INTERVAL '24 hour' THEN 1 ELSE 0 END) AS h_0_24,
              SUM(CASE WHEN last_seen_at <  NOW() - INTERVAL '24 hour'
                        AND last_seen_at >= NOW() - INTERVAL '72 hour' THEN 1 ELSE 0 END) AS h_24_72,
              SUM(CASE WHEN last_seen_at <  NOW() - INTERVAL '72 hour'
                        AND last_seen_at >= NOW() - INTERVAL '168 hour' THEN 1 ELSE 0 END) AS h_72_168,
              SUM(CASE WHEN last_seen_at <  NOW() - INTERVAL '168 hour'
                        AND last_seen_at >= NOW() - INTERVAL '336 hour' THEN 1 ELSE 0 END) AS h_168_336,
              SUM(CASE WHEN last_seen_at <  NOW() - INTERVAL '336 hour'
                        AND last_seen_at >= NOW() - INTERVAL '720 hour' THEN 1 ELSE 0 END) AS h_336_720,
              SUM(CASE WHEN last_seen_at IS NULL OR last_seen_at < NOW() - INTERVAL '720 hour' THEN 1 ELSE 0 END) AS h_720_inf
            FROM users
            """, nativeQuery = true)
    List<Object[]> countInactiveBuckets();

    /**
     * 주차별 코호트: 해당 주에 가입한 유저의 최근 30일 활동 여부
     */
    @Query(value = """
            SELECT
              date_trunc('week', created_at)::date AS cohort_week,
              COUNT(*) AS cohort_total,
              SUM(CASE WHEN last_seen_at IS NOT NULL AND last_seen_at >= NOW() - INTERVAL '30 days' THEN 1 ELSE 0 END) AS active_recent_30d
            FROM users
            GROUP BY cohort_week
            ORDER BY cohort_week DESC
            LIMIT 26
            """, nativeQuery = true)
    List<Object[]> cohortRetention30d();

    /**
     * 최근 7일 last_seen_at 시간대(HOUR) 분포
     */
    @Query(value = """
            SELECT EXTRACT(HOUR FROM last_seen_at) AS hour_of_day, COUNT(*) AS cnt
            FROM users
            WHERE last_seen_at IS NOT NULL
              AND last_seen_at >= NOW() - INTERVAL '7 days'
            GROUP BY hour_of_day
            ORDER BY hour_of_day
            """, nativeQuery = true)
    List<Object[]> lastSeenHourDist7d();

    /**
     * 비활성(최근 30일 미접속) / 전체 사용자 수
     */
    @Query(value = """
  SELECT
    SUM(CASE WHEN last_seen_at IS NULL
              OR last_seen_at < NOW() - INTERVAL '30 day' THEN 1 ELSE 0 END) AS inactive_30d,
    COUNT(*) AS total_users
  FROM users
  """, nativeQuery = true)
    List<Object[]> countInactive30dAndTotal();

    @Query(value = """
        SELECT 
            SUM(CASE WHEN last_seen_at < NOW() - INTERVAL '30 day' OR last_seen_at IS NULL THEN 1 ELSE 0 END) as inactive30,
            COUNT(*) as total,
            SUM(CASE WHEN last_seen_at < NOW() - INTERVAL '7 day' OR last_seen_at IS NULL THEN 1 ELSE 0 END) as inactive7,
            SUM(CASE WHEN last_seen_at < NOW() - INTERVAL '3 day' OR last_seen_at IS NULL THEN 1 ELSE 0 END) as inactive3
        FROM users
    """, nativeQuery = true)
    List<Object[]> countInactiveStatsAndTotal();

    /**
     * 최근 N주: KST(Asia/Seoul) 기준 "캘린더 주" 단위 VISITOR/전체 집계
     * 반환: [week_kst(yyyy-MM-dd, 주 시작일), total_users, visitors]
     */
    @Query(value = """
    SELECT
      date_trunc('week', (u.created_at AT TIME ZONE 'Asia/Seoul'))::date AS week_kst,
      COUNT(*) AS total_users,
      SUM(CASE WHEN user_role = 'USER' THEN 1 ELSE 0 END) AS users
    FROM users u
    WHERE u.created_at >= :from AND u.created_at < :to
    GROUP BY week_kst
    ORDER BY week_kst
    """, nativeQuery = true)
    List<Object[]> visitorShareWeekly(
            @Param("from") Instant fromIsoDateTimeUtc,
            @Param("to") Instant toIsoDateTimeUtc
    );

    /**
     * 이번 주(캘린더 주, KST 기준) VISITOR/전체 스냅샷
     * 반환: [total_users, visitors]
     */
    @Query(value = """
    WITH bounds AS (
      SELECT
        date_trunc('week', (NOW() AT TIME ZONE 'Asia/Seoul')) AT TIME ZONE 'Asia/Seoul' AS w_start_kst,
        (date_trunc('week', (NOW() AT TIME ZONE 'Asia/Seoul')) + INTERVAL '7 day') AT TIME ZONE 'Asia/Seoul' AS w_end_kst
    )
    SELECT
      COUNT(*) AS total_users,
      SUM(CASE WHEN user_role = 'USER' THEN 1 ELSE 0 END) AS users
    FROM users u, bounds b
    WHERE u.created_at >= (b.w_start_kst AT TIME ZONE 'UTC')
      AND u.created_at <  (b.w_end_kst   AT TIME ZONE 'UTC')
    """, nativeQuery = true)
    Object[] visitorShareCurrentWeek();

    @Query("SELECT u FROM User u")
    Stream<User> findAllAsStream();

    @Query(value = "SELECT COUNT(*) FROM users WHERE created_at >= NOW() - (INTERVAL '1 day' * :days)", nativeQuery = true)
    long countRecentSignups(@Param("days") int days);

    @Query(value = """
        SELECT COUNT(*) FROM users 
        WHERE created_at <= NOW() - (INTERVAL '1 day' * :joinDays)
          AND last_seen_at >= NOW() - (INTERVAL '1 day' * :activeDays)
    """, nativeQuery = true)
    long countEffectiveActiveUsers(@Param("joinDays") int joinDays, @Param("activeDays") int activeDays);

    @Query(value = """
        SELECT COUNT(*) FROM users 
        WHERE created_at <= NOW() - (INTERVAL '1 day' * :joinDays)
          AND last_seen_at >= NOW() - INTERVAL '24 hours'
    """, nativeQuery = true)
    long countRecentActiveExistingUsers(@Param("joinDays") int joinDays);

    @Query(value = """
        SELECT COUNT(*) FROM users 
        WHERE created_at BETWEEN (NOW() - INTERVAL '8 days') AND (NOW() - INTERVAL '7 days')
    """, nativeQuery = true)
    long countUsersJoined7DaysAgo();

    @Query(value = """
        SELECT COUNT(*) FROM users 
        WHERE created_at BETWEEN (NOW() - INTERVAL '8 days') AND (NOW() - INTERVAL '7 days')
          AND last_seen_at >= NOW() - INTERVAL '24 hours'
    """, nativeQuery = true)
    long countUsersJoined7DaysAgoAndActiveToday();

    @Query(value = "SELECT COUNT(*) FROM users WHERE last_seen_at >= NOW() - INTERVAL '7 days'", nativeQuery = true)
    long countActiveUsersLast7Days();

    // 최근 1일 활동 유저 수 (DAU)
    @Query(value = "SELECT COUNT(*) FROM users WHERE last_seen_at >= NOW() - INTERVAL '1 day'", nativeQuery = true)
    long countActiveUsersLast1Day();

    // 최근 30일 활동 유저 수 (MAU)
    @Query(value = "SELECT COUNT(*) FROM users WHERE last_seen_at >= NOW() - INTERVAL '30 days'", nativeQuery = true)
    long countActiveUsersLast30Days();

    @Query(value = """
    SELECT
      COUNT(*) AS total_users,
      SUM(CASE WHEN user_role = 'USER' THEN 1 ELSE 0 END) AS users
    FROM users
    """, nativeQuery = true)
    Object[] visitorShareOverall();

    @Query("SELECT COUNT(u) FROM User u WHERE u.createdAt BETWEEN :start AND :end")
    long countUsersJoinedInPeriod(@Param("start") Instant start, @Param("end") Instant end);

    @Query(value = """
        SELECT COUNT(*) FROM users 
        WHERE created_at BETWEEN :start AND :end
          AND last_seen_at >= NOW() - INTERVAL '24 hours'
    """, nativeQuery = true)
    long countUsersJoinedInPeriodAndActiveToday(@Param("start") Instant start, @Param("end") Instant end);

    List<User> findAllByUserRole(Role userRole);

    @Query("SELECT cp.user FROM ChatParticipant cp " +
           "WHERE cp.chatRoom.id = :chatRoomId AND cp.user.id != :senderId")
    List<User> findPartnersByChatRoomId(@Param("chatRoomId") Long chatRoomId, @Param("senderId") Long senderId);

    @Query("SELECT new core.domain.user.dto.StringCountDto(u.country, COUNT(u)) " +
            "FROM User u " +
            "WHERE u.country IS NOT NULL AND u.country != '' " +
            "GROUP BY u.country " +
            "ORDER BY COUNT(u) DESC")
    List<StringCountDto> countUsersByCountry();

    @Query("SELECT new core.domain.user.dto.StringCountDto(u.language, COUNT(u)) " +
            "FROM User u " +
            "WHERE u.language IS NOT NULL AND u.language != '' " +
            "GROUP BY u.language " +
            "ORDER BY COUNT(u) DESC")
    List<StringCountDto> countUsersByLanguage();

    @Query("SELECT u.country, COUNT(u), " +
            "SUM(CASE WHEN u.lastSeenAt >= :activeThreshold THEN 1 ELSE 0 END) " +
            "FROM User u " +
            "WHERE u.createdAt BETWEEN :periodStart AND :periodEnd " +
            "AND u.country IS NOT NULL AND u.country != '' " +
            "GROUP BY u.country " +
            "HAVING COUNT(u) > 0 " +
            "ORDER BY COUNT(u) DESC")
    List<Object[]> aggregateCountryRetention(
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd,
            @Param("activeThreshold") Instant activeThreshold
    );

    @Query("SELECT DISTINCT u.country FROM User u WHERE u.country IS NOT NULL AND u.country <> '' ORDER BY u.country")
    List<String> findDistinctCountries();

    @Query("SELECT t.deviceToken FROM UserDeviceToken t JOIN t.user u WHERE u.country = :country AND u.agreedToPushNotification = true")
    List<String> findDeviceTokensByCountry(@Param("country") String country);

    @Query(value = """
            SELECT COUNT(DISTINCT u.user_id)
            FROM users u
            INNER JOIN image i ON u.user_id = i.related_id
            WHERE u.created_at BETWEEN :start AND :end
              AND i.image_type = 'USER'
              AND i.url NOT LIKE '%/default/character%'
            """, nativeQuery = true)
    long countUsersWithCustomProfile(@Param("start") Instant start, @Param("end") Instant end);

    @Query(value = """
        SELECT
            COUNT(CASE WHEN diff_seconds <= 60 THEN 1 END) as within_1min,
            COUNT(CASE WHEN diff_seconds > 60 AND diff_seconds <= 3600 THEN 1 END) as within_1hour,
            COUNT(CASE WHEN diff_seconds > 3600 THEN 1 END) as after_1hour,
            COUNT(CASE WHEN first_msg_time IS NULL THEN 1 END) as never
        FROM (
            SELECT
                u.user_id,
                MIN(cm.sent_at) as first_msg_time,
                EXTRACT(EPOCH FROM (MIN(cm.sent_at) - u.created_at)) as diff_seconds
            FROM users u
            LEFT JOIN chat_message cm ON u.user_id = cm.sender_id
            WHERE u.created_at BETWEEN :start AND :end
            GROUP BY u.user_id
        ) sub
    """, nativeQuery = true)
    Object[] analyzeFirstMessageTime(@Param("start") Instant start, @Param("end") Instant end);

    @Query("SELECT u.sex, COUNT(u) " +
            "FROM User u " +
            "WHERE u.createdAt BETWEEN :start AND :end " +
            "GROUP BY u.sex")
    List<Object[]> countGenderByPeriod(@Param("start") Instant start, @Param("end") Instant end);

    @Query(value = """
        SELECT
            FLOOR((EXTRACT(YEAR FROM CURRENT_DATE) - CAST(SPLIT_PART(u.birth_date, '/', 3) AS INTEGER)) / 10) * 10 as age_group,
            COUNT(*)
        FROM users u
        WHERE u.created_at BETWEEN :start AND :end
          AND u.birth_date IS NOT NULL 
          AND u.birth_date LIKE '%/%/%' -- 형식이 MM/DD/YYYY 인 데이터만 안전하게 포함
        GROUP BY age_group
        ORDER BY age_group
    """, nativeQuery = true)
    List<Object[]> countAgeGroupByPeriod(@Param("start") Instant start, @Param("end") Instant end);

    @Query(value = """
        SELECT COUNT(*)
        FROM users u
        WHERE u.created_at BETWEEN :start AND :end
          AND NOT EXISTS (SELECT 1 FROM chat_message cm WHERE cm.sender_id = u.user_id)
          AND NOT EXISTS (SELECT 1 FROM comment c WHERE c.user_id = u.user_id)
          AND NOT EXISTS (SELECT 1 FROM post p WHERE p.user_id = u.user_id)
          AND NOT EXISTS (SELECT 1 FROM chat_room cr WHERE cr.owner_id = u.user_id)
          AND NOT EXISTS (SELECT 1 FROM chat_participant cp WHERE cp.user_id = u.user_id)
    """, nativeQuery = true)
    long countGhostUsers(@Param("start") Instant start, @Param("end") Instant end);

    @Modifying(clearAutomatically = true)
    @Query(value = """
    UPDATE users u
    SET reply_rate = stats.calculated_rate
    FROM (
        SELECT 
            cp.user_id,
            COALESCE(
                SUM(CASE WHEN cm.sender_id = cp.user_id THEN 1.0 ELSE 0.0 END) 
                / NULLIF(COUNT(cm.message_id), 0)
            , 0.0) as calculated_rate
        FROM chat_participant cp
        JOIN chat_room cr ON cp.chatroom_id = cr.chatroom_id
        JOIN chat_message cm ON cr.chatroom_id = cm.chatroom_id
        WHERE cr.is_group = false
        GROUP BY cp.user_id
    ) stats
    WHERE u.user_id = stats.user_id
""", nativeQuery = true)
    void updateReplyRatesBulk();

}

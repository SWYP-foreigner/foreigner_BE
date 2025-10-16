package core.domain.user.repository;


import core.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByProviderAndSocialId(String provider, String socialId);

    Optional<User> findByEmail(String email);

    Optional<User> findByName(String name);

    Optional<User> getUserById(Long id);

    boolean existsByEmail(String email);

    @Query("SELECT u FROM User u WHERE u.id NOT IN :excludeIds " +
           "AND u.purpose IS NOT NULL AND u.purpose <> '' " +
           "AND u.country IS NOT NULL AND u.country <> '' " +
           "AND u.birthdate IS NOT NULL AND u.birthdate <> '' " +
           "AND u.language IS NOT NULL AND u.language <> ''")
    List<User> findFullProfiledRecommendationCandidates(@Param("excludeIds") Collection<Long> excludeIds);

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
    Object[] countInactiveBuckets();

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

    /**
     * 최근 N주: KST(Asia/Seoul) 기준 "캘린더 주" 단위 VISITOR/전체 집계
     * 반환: [week_kst(yyyy-MM-dd, 주 시작일), total_users, visitors]
     */
    @Query(value = """
    SELECT
      date_trunc('week', (u.created_at AT TIME ZONE 'Asia/Seoul'))::date AS week_kst,
      COUNT(*) AS total_users,
      SUM(CASE WHEN u.user_role = 'VISITOR' THEN 1 ELSE 0 END) AS visitors
    FROM users u
    WHERE u.created_at >= :from AND u.created_at < :to
    GROUP BY week_kst
    ORDER BY week_kst
    """, nativeQuery = true)
    List<Object[]> visitorShareWeekly(
            @Param("from") String fromIsoDateTimeUtc,
            @Param("to")   String toIsoDateTimeUtc
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
      SUM(CASE WHEN u.user_role = 'VISITOR' THEN 1 ELSE 0 END) AS visitors
    FROM users u, bounds b
    WHERE u.created_at >= (b.w_start_kst AT TIME ZONE 'UTC')
      AND u.created_at <  (b.w_end_kst   AT TIME ZONE 'UTC')
    """, nativeQuery = true)
    Object[] visitorShareCurrentWeek();

}

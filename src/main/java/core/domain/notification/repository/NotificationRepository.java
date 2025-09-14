package core.domain.notification.repository;

import core.domain.notification.entity.Notification;
import core.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * 특정 사용자의 모든 알림을 최신순으로 조회합니다.
     * @param user 알림을 조회할 사용자
     * @return 알림 목록 (최신순)
     */
    List<Notification> findByUserOrderByCreatedAtDesc(User user);

    /**
     * 특정 사용자의 읽지 않은 알림 개수를 조회합니다.
     * (알림 아이콘에 뱃지를 표시할 때 유용합니다.)
     * @param user 개수를 조회할 사용자
     * @return 읽지 않은 알림의 수
     */
    long countByUserAndReadIsFalse(User user);
}
package core.domain.notification.repository;

import core.domain.notification.entity.Notification;
import core.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    void deleteAllByUserId(Long userId);
}
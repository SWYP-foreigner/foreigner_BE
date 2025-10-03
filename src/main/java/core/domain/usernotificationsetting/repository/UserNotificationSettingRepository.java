package core.domain.usernotificationsetting.repository;

import core.domain.user.entity.User;
import core.domain.usernotificationsetting.entity.UserNotificationSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface UserNotificationSettingRepository extends JpaRepository<UserNotificationSetting, Long> {
    List<UserNotificationSetting> findAllByUser(User user);

    boolean existsByUser(User user);
}
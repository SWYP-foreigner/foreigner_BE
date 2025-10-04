package core.domain.notification.service;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import core.domain.notification.dto.NotificationEvent;
import core.domain.notification.entity.Notification;
import core.domain.notification.repository.NotificationRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.domain.userdevicetoken.entity.UserDeviceToken;
import core.domain.userdevicetoken.repository.UserDeviceTokenRepository;
import core.domain.usernotificationsetting.repository.UserNotificationSettingRepository;
import core.global.exception.BusinessException;
import core.global.enums.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final UserNotificationSettingRepository userNotificationSettingRepository;
    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final FirebaseMessaging firebaseMessaging;

    public void createNotification(NotificationEvent event) {
        User recipient = userRepository.findById(event.recipientUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        boolean enabled = userNotificationSettingRepository
                .existsByUserIdAndNotificationTypeAndEnabledTrue(recipient.getId(), event.type());

        if (!enabled) {
            return;
        }

        Notification notification = Notification.builder()
                .user(recipient)
                .message(event.message())
                .notificationType(event.type())
                .referenceId(event.referenceId())
                .build();

        notificationRepository.save(notification);

        List<UserDeviceToken> tokens = userDeviceTokenRepository.findAllByUserId(recipient.getId());

        for (UserDeviceToken token : tokens) {
            try {
                Message message = Message.builder()
                        .setToken(token.getDeviceToken())
                        .putData("type", event.type().name())
                        .putData("message", event.message())
                        .putData("referenceId", String.valueOf(event.referenceId()))
                        .build();

                firebaseMessaging.sendAsync(message);
            } catch (Exception e) {
                log.error("FCM 전송 실패: 토큰={}, 오류={}", token.getDeviceToken(), e.getMessage());
            }
        }
    }
}
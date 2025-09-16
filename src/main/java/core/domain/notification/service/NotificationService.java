package core.domain.notification.service;

import core.domain.notification.dto.NotificationEvent;
import core.domain.notification.entity.Notification;
import core.domain.notification.repository.NotificationRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    public void createNotification(NotificationEvent event) {
        User recipient = userRepository.findById(event.recipientUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Notification notification = Notification.builder()
                .user(recipient)
                .message(event.message())
                .notificationType(event.type())
                .referenceId(event.referenceId())
                .build();

        notificationRepository.save(notification);
    }
}
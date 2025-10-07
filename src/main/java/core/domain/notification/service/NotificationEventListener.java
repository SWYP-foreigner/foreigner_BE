package core.domain.notification.service;

import core.domain.notification.dto.NotificationEvent;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional; // ✅ @Transactional 임포트

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final UserRepository userRepository;
    private final UserNotificationService notificationService;
    private final PushNotificationService pushNotificationService;
    private final NotificationMessageGenerator notificationMessageGenerator;


    @Async("dispatchExecutor")
    @EventListener
    @Transactional
    public void handleNotificationEvent(NotificationEvent event) {
        try {

            User recipient = userRepository.findById(event.recipientId())
                    .orElse(null);
            User actor = userRepository.findById(event.actorId())
                    .orElse(null);

            if (recipient == null || actor == null) {
                log.warn("알림 이벤트 처리 실패: 수신자 또는 발신자를 찾을 수 없습니다. Event: {}", event);
                return;
            }

            log.info("알림 이벤트 수신: recipientId={}, actorId={}", recipient.getId(), actor.getId());

            String message = notificationMessageGenerator.generateMessage(actor, event);
            notificationService.createAndSaveNotification(recipient, event, message);
            pushNotificationService.sendPushNotification(recipient, event, message);

        } catch (Exception e) {
            log.error("알림 이벤트 처리 중 오류 발생: {}", event, e);
        }
    }
}
package core.domain.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;


import core.domain.notification.dto.NotificationEvent;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final UserNotificationService notificationService;
    private final PushNotificationService pushNotificationService;
    private final NotificationMessageGenerator notificationMessageGenerator;

    @Async
    @EventListener
    public void handleNotificationEvent(NotificationEvent event) {
        try {
            log.info("알림 이벤트 수신: {}", event);
            String message = notificationMessageGenerator.generateMessage(event);
            notificationService.createAndSaveNotification(event, message);
            pushNotificationService.sendPushNotification(event, message);

        } catch (Exception e) {
            log.error("알림 이벤트 처리 중 오류 발생: {}", event, e);
        }
    }
}
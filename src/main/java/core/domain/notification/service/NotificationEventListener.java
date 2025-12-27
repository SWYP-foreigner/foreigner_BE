package core.domain.notification.service;
import core.domain.notification.dto.NotificationBulkEvent;
import core.domain.notification.entity.Notification;
import core.domain.notification.repository.NotificationRepository;
import core.domain.userdevicetoken.repository.UserDeviceTokenRepository;
import core.global.enums.NotificationType;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.util.StopWatch;
import core.domain.notification.dto.NewUserJoinedEvent;
import core.domain.notification.dto.NotificationEvent;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {
    private final EntityManager entityManager;
    private final UserRepository userRepository;
    private final UserNotificationService notificationService;
    private final PushNotificationService pushNotificationService;
    private final NotificationMessageGenerator notificationMessageGenerator;
    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final NotificationRepository notificationRepository;

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
            notificationService.createAndSaveNotification(recipient, actor, event, message);
            pushNotificationService.sendPushNotification(recipient, event, message);

        } catch (Exception e) {
            log.error("알림 이벤트 처리 중 오류 발생: {}", event, e);
        }
    }

    /**
     * ✅ [최적화된 버전]
     * DB 저장 X, 배치 발송 O, 메모리 효율 O
     */
    @Async("dispatchExecutor")
    @EventListener
    public void handleNewUserBroadcastOptimized(NewUserJoinedEvent event) {

        User newUser = userRepository.findById(event.newUserId()).orElse(null);
        if (newUser == null) {
            log.warn("신규 유저 정보를 찾을 수 없어 브로드캐스트를 중단합니다. ID: {}", event.newUserId());
            return;
        }

        log.info("📢 신규 유저({}) 브로드캐스트 시작", newUser.getId());
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        List<String> targetCountries = List.of("South Korea", "KR", "Korea");
        Instant activeSince = Instant.now().minus(7, ChronoUnit.DAYS);
        NotificationType notiType = NotificationType.newuser;

        String title = "New friend arrived! 👋";
        String body = determineMessageInEnglish(newUser);

        int batchSize = 500;
        Pageable pageable = PageRequest.of(0, batchSize);
        int totalSent = 0;

        while (true) {
            Slice<String> tokenSlice = userDeviceTokenRepository.findTokensForBroadcast(
                    targetCountries, activeSince, notiType, pageable
            );

            List<String> tokens = tokenSlice.getContent();

            if (!tokens.isEmpty()) {
                // DB 저장 없이 바로 푸시 발송
                pushNotificationService.sendBatchPush(tokens, title, body, newUser.getId());
                totalSent += tokens.size();
            }

            if (!tokenSlice.hasNext()) break;
            pageable = tokenSlice.nextPageable();
        }

        stopWatch.stop();
        log.info("========== [브로드캐스트 결과] ==========");
        log.info("총 소요 시간: {} 초", stopWatch.getTotalTimeSeconds());
        log.info("발송 건수: {} 건", totalSent);
        log.info("========================================");
    }

    private String determineMessageInEnglish(User newUser) {
        String country = newUser.getCountry();
        if (country == null || country.isBlank()) {
            return "A new friend has joined! Say hello.";
        }

        country = country.trim();
        boolean isKorean = country.equalsIgnoreCase("South Korea") || country.equalsIgnoreCase("KR");

        if (isKorean) {
            return "A new Korean friend has joined! Say hello.";
        } else {
            return String.format("A new friend from %s has joined!", country);
        }
    }

    @Async
    @EventListener
    public void handleBulkNotification(NotificationBulkEvent event) {
        String safeMessage = truncate(event.content(), 100);

        User senderProxy = entityManager.getReference(User.class, event.senderId());

        List<Notification> notifications = event.recipientIds().stream()
                .map(targetId -> {
                    User receiverProxy = entityManager.getReference(User.class, targetId);
                    Long referenceId = null;
                    try {
                        referenceId = Long.parseLong(String.valueOf(event.roomId()));
                    } catch (NumberFormatException e) {
                        // ignore
                    }

                    return Notification.builder()
                            .user(receiverProxy)
                            .actor(senderProxy)
                            .message(safeMessage)
                            .notificationType(event.type())
                            .referenceId(referenceId)
                            .subReferenceId(null)
                            .build();
                })
                .toList();

        notificationRepository.saveAll(notifications);
        pushNotificationService.sendGroupPush(event.recipientIds(),safeMessage, String.valueOf(event.roomId()), event.roomName(), event.senderId());
    }
    private String truncate(String input, int maxLength) {
        if (input == null) return null;
        if (input.length() <= maxLength) return input;

        // 3글자("...") 공간을 확보하기 위해 -3
        return input.substring(0, maxLength - 3) + "...";
    }
}
package core.domain.notification.service;

import com.google.firebase.messaging.*;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.notification.dto.NotificationEvent;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.domain.userdevicetoken.entity.UserDeviceToken;
import core.domain.userdevicetoken.repository.UserDeviceTokenRepository;
import core.domain.usernotificationsetting.entity.UserNotificationSetting;
import core.domain.usernotificationsetting.repository.UserNotificationSettingRepository;
import core.global.enums.NotificationType;
import core.global.metrics.NotificationMetrics;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PushNotificationService {

    private final FirebaseMessaging firebaseMessaging;
    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final UserNotificationSettingRepository userNotificationSettingRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final NotificationMetrics notificationMetrics;
    private final UserRepository userRepository;

    /**
     * 사용자에게 푸시 알림을 발송합니다.
     * SENDER_ID_MISMATCH 발생 시 로그 없이 토큰을 삭제합니다.
     */
    @Transactional
    @CircuitBreaker(name = "fcmPush", fallbackMethod = "fcmFallback")
    public void sendPushNotification(User recipient, NotificationEvent event, String message) throws FirebaseMessagingException {

        if (!recipient.isAgreedToPushNotification()) {
            log.info("사용자 ID {}: 마스터 스위치 OFF. 푸시 알림을 발송하지 않습니다.", recipient.getId());
            return;
        }

        if (event.notificationType().isConfigurable()) {
            Optional<UserNotificationSetting> categorySettingOpt = userNotificationSettingRepository.findByUserAndNotificationType(recipient, event.notificationType());
            boolean isCategoryEnabled = categorySettingOpt.map(UserNotificationSetting::isEnabled).orElse(true);

            if (!isCategoryEnabled) {
                log.info("사용자 ID {}: '{}' 카테고리 스위치 OFF. 푸시 알림을 발송하지 않습니다.", recipient.getId(), event.notificationType());
                return;
            }
        }

        if (event.notificationType() == NotificationType.chat) {
            boolean isRoomNotificationsEnabled;
            Optional<ChatParticipant> participantOpt = chatParticipantRepository.findByChatRoomIdAndUserId(event.referenceId(), recipient.getId());
            isRoomNotificationsEnabled = participantOpt.map(ChatParticipant::isNotificationsEnabled).orElse(false);

            if (!isRoomNotificationsEnabled) {
                log.info("사용자 ID {}: 채팅방 ID {} 음소거 상태. 푸시 알림을 발송하지 않습니다.", recipient.getId(), event.referenceId());
                return;
            }
        }
        List<UserDeviceToken> deviceTokens = userDeviceTokenRepository.findAllByUser(recipient);
        for (UserDeviceToken userDeviceToken : deviceTokens) {
            long start = System.currentTimeMillis();

            Message.Builder messageBuilder = Message.builder()
                    .setToken(userDeviceToken.getDeviceToken())
                    .setNotification(com.google.firebase.messaging.Notification.builder()
                            .setTitle("Kori")
                            .setBody(message)
                            .build())
                    .putData("notificationType", event.notificationType().name());

            switch (event.notificationType()) {
                case post:
                    messageBuilder
                            .putData("type", "post")
                            .putData("postId", String.valueOf(event.referenceId()));
                    if (event.commentId() != null) {
                        messageBuilder.putData("commentId", String.valueOf(event.commentId()));
                    }
                    break;
                case comment:
                    messageBuilder
                            .putData("type", "comment")
                            .putData("postId", String.valueOf(event.referenceId()));
                    if (event.commentId() != null) {
                        messageBuilder.putData("commentId", String.valueOf(event.commentId()));
                    }
                    break;
                case follow:
                    messageBuilder
                            .putData("type", "follow")
                            .putData("friendId", String.valueOf(event.actorId()));
                    break;
                case receive:
                    messageBuilder
                            .putData("type", "receive")
                            .putData("followerId", String.valueOf(event.actorId()));
                    break;
                case chat:
                    String roomName = event.roomName();
                    if (roomName == null) {
                        roomName = "Unknown Chat Room";
                        log.warn("Chat notification event is missing roomName. ChatRoom ID: {}. Using default value '{}'.", event.referenceId(), roomName);
                    }

                    messageBuilder
                            .putData("type", "chat")
                            .putData("roomId", String.valueOf(event.referenceId()))
                            .putData("myId", String.valueOf(recipient.getId()))
                            .putData("roomName", roomName);
                    break;
                case followuserpost:
                    messageBuilder
                            .putData("type", "followuserpost")
                            .putData("postId", String.valueOf(event.referenceId()));
                    break;
                default:
                    break;
            }

            Message fcmMessage = messageBuilder.build();
            firebaseMessaging.send(fcmMessage);
            try {
                firebaseMessaging.send(fcmMessage);
                log.info("사용자 ID {} 에게 푸시 알림을 성공적으로 발송했습니다. (기기 토큰: ...{})", recipient.getId(), userDeviceToken.getDeviceToken().substring(Math.max(0, userDeviceToken.getDeviceToken().length() - 5)));

                notificationMetrics.mark("push", "sent", "ok");
                notificationMetrics.recordSend("push", "firebase", start);

            } catch (FirebaseMessagingException e) {
                MessagingErrorCode code = e.getMessagingErrorCode();
                String errorMessage = e.getMessage();
                String reason = "unknown";

                if (code == MessagingErrorCode.SENDER_ID_MISMATCH) {
                    userDeviceTokenRepository.delete(userDeviceToken);
                    reason = "invalid_token_mismatch";
                    // 로그 생략 (원한다면 debug 레벨로 추가 가능)
                }
                else if (code == MessagingErrorCode.UNREGISTERED ||
                        (code == MessagingErrorCode.INVALID_ARGUMENT && errorMessage != null && errorMessage.contains("registration token"))) {
                    log.info("만료/무효 토큰 삭제: {} (코드: {})", userDeviceToken.getDeviceToken(), code);
                    userDeviceTokenRepository.delete(userDeviceToken);
                    reason = "invalid_token";
                }
                // 재시도 필요
                else if (code == MessagingErrorCode.QUOTA_EXCEEDED ||
                        code == MessagingErrorCode.UNAVAILABLE ||
                        code == MessagingErrorCode.INTERNAL) {
                    log.warn("재시도 필요: {} (코드: {})", errorMessage, code);
                    reason = "retryable";
                }
                // 기타 에러
                else {
                    log.error("기타 FCM 에러: {} (코드: {})", errorMessage, code, e);
                }

                notificationMetrics.mark("push", "failed", reason);
                notificationMetrics.recordSend("push", "firebase", start);

                // Sender ID Mismatch가 아닐 때만 예외를 던짐 (로직 흐름 유지)
                if (code != MessagingErrorCode.SENDER_ID_MISMATCH &&
                        code != MessagingErrorCode.UNREGISTERED &&
                        code != MessagingErrorCode.INVALID_ARGUMENT) {
                    throw e;
                }
            }
        }
    }

    /**
     * [New] 채팅방 대량 알림 발송 (Listener에서 호출)
     */
    @Transactional
    @CircuitBreaker(name = "fcmPush", fallbackMethod = "fcmFallback")
    public void sendGroupPush(List<Long> recipientIds, String messageBody, String roomId, String roomName, Long senderId) {
        if (recipientIds == null || recipientIds.isEmpty()) return;

        List<UserDeviceToken> tokens = userDeviceTokenRepository.findAllByUserIdIn(recipientIds);

        if (tokens.isEmpty()) return;

        List<String> tokenStrings = tokens.stream()
                .map(UserDeviceToken::getDeviceToken)
                .distinct()
                .toList();

        Map<String, String> data = new HashMap<>();
        data.put("notificationType", NotificationType.chat.name());
        data.put("type", "chat");
        data.put("roomId", roomId);
        data.put("roomName", roomName != null ? roomName : "Chat Room");
        data.put("senderId", String.valueOf(senderId));

        List<List<String>> partitions = new ArrayList<>();
        int batchSize = 500;
        for (int i = 0; i < tokenStrings.size(); i += batchSize) {
            partitions.add(tokenStrings.subList(i, Math.min(i + batchSize, tokenStrings.size())));
        }

        for (List<String> batchTokens : partitions) {
            MulticastMessage message = MulticastMessage.builder()
                    .addAllTokens(batchTokens)
                    .setNotification(com.google.firebase.messaging.Notification.builder()
                            .setTitle("Kori")
                            .setBody(messageBody)
                            .build())
                    .putAllData(data)
                    .build();

            try {
                BatchResponse response = firebaseMessaging.sendEachForMulticast(message);

                if (response.getFailureCount() > 0) {
                    List<String> tokensToDelete = new ArrayList<>();
                    List<SendResponse> responses = response.getResponses();

                    for (int i = 0; i < responses.size(); i++) {
                        if (!responses.get(i).isSuccessful()) {
                            FirebaseMessagingException e = responses.get(i).getException();
                            MessagingErrorCode code = e.getMessagingErrorCode();
                            if (code == MessagingErrorCode.SENDER_ID_MISMATCH) {
                                tokensToDelete.add(batchTokens.get(i));
                            }
                            else if (code == MessagingErrorCode.UNREGISTERED ||
                                    code == MessagingErrorCode.INVALID_ARGUMENT) {
                                tokensToDelete.add(batchTokens.get(i));
                            }
                        }
                    }

                    if (!tokensToDelete.isEmpty()) {
                        userDeviceTokenRepository.deleteByDeviceTokenIn(tokensToDelete);
                    }
                }

                notificationMetrics.mark("push_batch", "sent", "ok");

            } catch (FirebaseMessagingException e) {
                log.error("💥 FCM Batch Request Failed", e);
                notificationMetrics.mark("push_batch", "error", "exception");
            }
        }
    }

    @Transactional
    public void sendPushToCountry(String targetCountry, String title, String body, Long adminId) {
        List<String> tokens = userRepository.findDeviceTokensByCountry(targetCountry);

        if (tokens.isEmpty()) {
            log.info("국가 '{}'에 발송할 대상 유저가 없습니다.", targetCountry);
            return;
        }

        log.info("국가 '{}' 타겟팅 푸시 시작: 대상 토큰 {}개", targetCountry, tokens.size());

        sendGenericBatchPush(tokens, title, body, "notice", adminId);
    }

    private void sendGenericBatchPush(List<String> tokens, String title, String body, String type, Long senderId) {
        List<List<String>> partitions = com.google.common.collect.Lists.partition(tokens, 500);

        for (List<String> batchTokens : partitions) {
            MulticastMessage message = MulticastMessage.builder()
                    .addAllTokens(batchTokens)
                    .setNotification(com.google.firebase.messaging.Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build())
                    .putData("notificationType", type)
                    .putData("type", type)
                    .putData("senderId", String.valueOf(senderId))
                    .build();

            try {
                BatchResponse response = firebaseMessaging.sendEachForMulticast(message);

                if (response.getFailureCount() > 0) {
                    handleBatchFailures(response, batchTokens);
                }

                notificationMetrics.mark("push_country", "sent", "ok");

            } catch (FirebaseMessagingException e) {
                log.error("국가 타겟팅 푸시 배치 발송 실패", e);
            }
        }
    }

    private void handleBatchFailures(BatchResponse response, List<String> batchTokens) {
        List<String> tokensToDelete = new ArrayList<>();
        List<SendResponse> responses = response.getResponses();

        for (int i = 0; i < responses.size(); i++) {
            if (!responses.get(i).isSuccessful()) {
                FirebaseMessagingException e = responses.get(i).getException();
                MessagingErrorCode code = e.getMessagingErrorCode();

                if (code == MessagingErrorCode.UNREGISTERED ||
                        code == MessagingErrorCode.INVALID_ARGUMENT ||
                        code == MessagingErrorCode.SENDER_ID_MISMATCH) {
                    tokensToDelete.add(batchTokens.get(i));
                }
            }
        }

        if (!tokensToDelete.isEmpty()) {
            userDeviceTokenRepository.deleteByDeviceTokenIn(tokensToDelete);
            log.info("유효하지 않은 토큰 {}개 삭제 완료", tokensToDelete.size());
        }
    }
    public void fcmFallback(User recipient, NotificationEvent event, String message, Throwable t) {
        log.error("❌ [Circuit Open] FCM 단일 발송 차단됨. 대상: {}, 사유: {}", recipient.getId(), t.getMessage());
        notificationMetrics.mark("push", "failed", "circuit_open");
        // 실패했다고 DB를 롤백시키지 않고 로그만 남기고 조용히 넘어감 (가용성 확보)
    }

    /**
     * 서킷이 열렸을 때 실행 (배치 발송용)
     */
    public void fcmBatchFallback(List<Long> recipientIds, String messageBody, String roomId, String roomName, Long senderId, Throwable t) {
        log.error("❌ [Circuit Open] FCM 배치 발송 차단됨. 대상 수: {}, 사유: {}", recipientIds.size(), t.getMessage());
        notificationMetrics.mark("push_batch", "failed", "circuit_open");
    }
}
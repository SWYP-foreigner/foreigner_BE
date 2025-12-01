package core.domain.notification.service;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.notification.dto.NotificationEvent;
import core.domain.user.entity.User;
import core.domain.userdevicetoken.entity.UserDeviceToken;
import core.domain.userdevicetoken.repository.UserDeviceTokenRepository;
import core.domain.usernotificationsetting.entity.UserNotificationSetting;
import core.domain.usernotificationsetting.repository.UserNotificationSettingRepository;
import core.global.enums.NotificationType;
import core.global.metrics.NotificationMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

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

    /**
     * 사용자에게 푸시 알림을 발송합니다. (람다 제거 버전)
     * 발송 전 3단계 동의 여부를 모두 확인합니다.
     * 클라이언트 이동을 위한 data 페이로드를 포함합니다.
     * @param event 알림 이벤트 데이터
     * @param message 사용자에게 보여줄 최종 메시지
     */
    @Transactional
    public void sendPushNotification(User recipient, NotificationEvent event, String message) throws FirebaseMessagingException {

        long start = System.currentTimeMillis();

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
            Message.Builder messageBuilder = Message.builder()
                    .setToken(userDeviceToken.getDeviceToken())
                    .setNotification(com.google.firebase.messaging.Notification.builder()
                            .setTitle("Foreigner")
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
                case newuser:
                    messageBuilder
                            .putData("type", "newuser")
                            .putData("userId", String.valueOf(event.referenceId()));
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

            try {
                firebaseMessaging.send(fcmMessage);
                log.info("사용자 ID {} 에게 푸시 알림을 성공적으로 발송했습니다. (기기 토큰: ...{})", recipient.getId(), userDeviceToken.getDeviceToken().substring(userDeviceToken.getDeviceToken().length() - 5));

                notificationMetrics.mark("push", "sent", "ok");
                notificationMetrics.recordSend("push", "firebase", start);
            } catch (FirebaseMessagingException e) {
                MessagingErrorCode code = e.getMessagingErrorCode();
                String errorMessage = e.getMessage();

                String reason = "unknown";



                if (code == MessagingErrorCode.UNREGISTERED ||
                        (code == MessagingErrorCode.INVALID_ARGUMENT && errorMessage != null && errorMessage.contains("registration token")) ||
                        code == MessagingErrorCode.SENDER_ID_MISMATCH) {
                    log.info("만료/무효 토큰 삭제: {} (코드: {})", userDeviceToken.getDeviceToken(), code);
                    userDeviceTokenRepository.delete(userDeviceToken);
                    reason = "invalid_token";

                    notificationMetrics.mark("push", "failed", reason);
                    notificationMetrics.recordSend("push", "firebase", start);
                } else if (code == MessagingErrorCode.QUOTA_EXCEEDED ||
                        code == MessagingErrorCode.UNAVAILABLE ||
                        code == MessagingErrorCode.INTERNAL) {
                    log.warn("재시도 필요: {} (코드: {})", errorMessage, code);
                    reason = "retryable";

                    notificationMetrics.mark("push", "failed", reason);
                    notificationMetrics.recordSend("push", "firebase", start);
                    throw e;  // 호출자에게 예외를 전파하여 재시도 처리
                } else {
                    log.error("기타 FCM 에러: {} (코드: {})", errorMessage, code, e);
                    // 여기에 fallback 로직 추가 가능 (e.g., 이메일 알림)
                }
            }
        }
    }
}
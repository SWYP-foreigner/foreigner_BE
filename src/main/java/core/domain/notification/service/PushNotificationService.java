package core.domain.notification.service;

import com.google.firebase.messaging.*;
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

import java.util.ArrayList;
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
                } else if (code == MessagingErrorCode.QUOTA_EXCEEDED ||
                           code == MessagingErrorCode.UNAVAILABLE ||
                           code == MessagingErrorCode.INTERNAL) {
                    log.warn("재시도 필요: {} (코드: {})", errorMessage, code);
                    reason = "retryable";
                } else {
                    log.error("기타 FCM 에러: {} (코드: {})", errorMessage, code, e);
                    // 여기에 fallback 로직 추가 가능 (e.g., 이메일 알림)
                }

                notificationMetrics.mark("push", "failed", reason);
                notificationMetrics.recordSend("push", "firebase", start);

                throw e;
            }
        }
    }
    @Transactional
    public void sendBatchPush(List<String> tokens, String title, String body, Long actorId) {
        if (tokens == null || tokens.isEmpty()) return;

        MulticastMessage message = MulticastMessage.builder()
                .addAllTokens(tokens)
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .putData("notificationType", NotificationType.newuser.name())
                .putData("type", "newuser")
                .putData("userId", String.valueOf(actorId))
                .build();

        try {
            long start = System.currentTimeMillis();
            BatchResponse response = firebaseMessaging.sendEachForMulticast(message);
            if (response.getFailureCount() > 0) {
                List<String> tokensToDelete = new ArrayList<>();
                List<SendResponse> responses = response.getResponses();

                for (int i = 0; i < responses.size(); i++) {
                    SendResponse sendResponse = responses.get(i);

                    if (!sendResponse.isSuccessful()) {
                        String failedToken = tokens.get(i);
                        FirebaseMessagingException e = sendResponse.getException();
                        MessagingErrorCode code = e.getMessagingErrorCode();
                        String errorMessage = e.getMessage();

                        if (code == MessagingErrorCode.UNREGISTERED ||
                                code == MessagingErrorCode.SENDER_ID_MISMATCH ||
                                (code == MessagingErrorCode.INVALID_ARGUMENT && errorMessage != null && errorMessage.contains("registration token"))) {

                            log.warn("🚨 만료/무효 토큰 감지 -> 삭제 예정: {} (코드: {})", failedToken, code);
                            tokensToDelete.add(failedToken);

                            notificationMetrics.mark("push_batch", "failed", "invalid_token");

                        }
                        else if (code == MessagingErrorCode.QUOTA_EXCEEDED ||
                                code == MessagingErrorCode.UNAVAILABLE ||
                                code == MessagingErrorCode.INTERNAL) {
                            log.warn("⚠️ FCM 서버 일시적 장애 (재시도 권장): {} (코드: {})", failedToken, code);
                            notificationMetrics.mark("push_batch", "failed", "retryable");
                        }
                        else {
                            log.error("❌ 기타 배치 발송 실패: {} (코드: {}, 에러: {})", failedToken, code, errorMessage);
                            notificationMetrics.mark("push_batch", "failed", "unknown");
                        }
                    } else {
                        notificationMetrics.mark("push_batch", "sent", "ok");
                    }
                }

                if (!tokensToDelete.isEmpty()) {
                    userDeviceTokenRepository.deleteByDeviceTokenIn(tokensToDelete);
                    log.info("🧹 총 {}개의 만료된 토큰을 DB에서 정리했습니다.", tokensToDelete.size());
                }
            }

            log.info("📊 배치 발송 완료: 요청 {}건 / 성공 {}건 / 실패 {}건 (소요시간: {}ms)",
                    tokens.size(), response.getSuccessCount(), response.getFailureCount(), System.currentTimeMillis() - start);

        } catch (FirebaseMessagingException e) {
            log.error("💥 FCM 배치 발송 요청 자체 실패", e);
            notificationMetrics.mark("push_batch", "error", "exception");
        }
    }
}
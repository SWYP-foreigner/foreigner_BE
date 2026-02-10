package core.domain.chat.service;

import core.domain.chat.dto.*;
import core.domain.notification.dto.NotificationBulkEvent;
import core.global.enums.chat.MessageType;
import core.global.enums.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatEventListener {

    // [변경 1] 우리가 만든 고성능 전송기 주입
    private final FastSocketSender fastSocketSender;

    // [유지] 단건 전송이나 방 단위 브로드캐스팅용으로 기존 템플릿도 필요함
    private final SimpMessagingTemplate messagingTemplate;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * [메시지 전송 이벤트] - 여기가 핵심 최적화 대상입니다.
     * DB 커밋 후 실행 (AFTER_COMMIT)
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMessageSent(MessageSentEvent event) {
        ChatMessageResponse message = event.messageResponse();
        ChatRoomSummaryResponse commonSummary = event.roomSummary();

        // 1. 알림(Notification) 전송 로직 (기존 유지)
        sendPushNotification(event, message, commonSummary);

        // =================================================================
        // [변경 2] 웹소켓 전송 최적화 (Zero-Copy 적용)
        // 기존: parallelStream 루프 -> 변경: JSON 1회 변환 후 헤더만 바꿔서 전송
        // =================================================================

        List<Long> recipients = event.recipientIds();
        if (recipients == null || recipients.isEmpty()) return;

        // A. 채팅방 내부 메시지 전송 (NEW_MESSAGE)
        // 목표 주소: /topic/user/{userId}/{roomId}/messages
        // Suffix:   /{roomId}/messages
        String messageSuffix = "/" + message.roomId() + "/messages";
        TypedWebSocketResponse<ChatMessageResponse> messagePayload =
                new TypedWebSocketResponse<>("NEW_MESSAGE", message);

        // 1,000명에게 쏠 때, JSON 변환은 여기서 딱 1번만 일어납니다.
        fastSocketSender.sendToUsersFast(recipients, messageSuffix, messagePayload);


        // B. 채팅방 목록 갱신 (ROOM_UPDATE)
        // 목표 주소: /topic/user/{userId}/rooms
        // Suffix:   /rooms
        if (commonSummary != null) {
            // 목록 갱신용 DTO 생성 (내용이 모두 같으므로 1개만 생성)
            ChatRoomSummaryResponse fastSummary = new ChatRoomSummaryResponse(
                    commonSummary.roomId(),
                    commonSummary.roomName(),
                    commonSummary.lastMessageContent(),
                    commonSummary.lastMessageTime(),
                    commonSummary.roomImageUrl(),
                    commonSummary.unreadCount(),
                    commonSummary.participantCount()
            );

            String roomSuffix = "/rooms";
            TypedWebSocketResponse<ChatRoomSummaryResponse> roomPayload =
                    new TypedWebSocketResponse<>("ROOM_UPDATE", fastSummary);

            // 목록 갱신도 Zero-Copy로 전송
            fastSocketSender.sendToUsersFast(recipients, roomSuffix, roomPayload);
        }
    }


    /**
     * [메시지 읽음 처리 이벤트]
     * 여기는 대량 발송이 아니라 특정 방(Room) 단위거나 단건이므로
     * 기존 messagingTemplate을 써도 무방합니다.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMessageRead(MessageReadEvent event) {
        // 1. 말풍선 옆 숫자 갱신 (채팅방 내부) -> /topic/rooms/... (브로드캐스트)
        if (!event.updatedReadCounts().isEmpty()) {
            messagingTemplate.convertAndSend(
                    "/topic/rooms/" + event.roomId() + "/read-counts",
                    new TypedWebSocketResponse<>(
                            "READ_COUNT_UPDATE",
                            new MessageReadCountUpdateResponse(event.updatedReadCounts())
                    )
            );
        }

        // 2. 채팅방 목록의 빨간 배지 갱신 (특정 유저 1명) -> Unicast
        if (event.roomSummary() != null) {
            messagingTemplate.convertAndSend(
                    "/topic/user/" + event.readerId() + "/rooms",
                    new TypedWebSocketResponse<>("ROOM_UPDATE", event.roomSummary())
            );
        }
    }

    /**
     * [메시지 삭제 이벤트]
     * 방 전체 브로드캐스트이므로 기존 템플릿 유지
     */
    @EventListener
    @Async
    public void handleMessageDeleted(MessageDeletedEvent event) {
        Map<String, String> payload = Map.of(
                "type", "MESSAGE_DELETE",
                "id", event.messageId().toString()
        );
        messagingTemplate.convertAndSend("/topic/rooms/" + event.roomId(), payload);
        log.info("Broadcasted delete event for message {} in room {}", event.messageId(), event.roomId());
    }

    // (알림 로직 분리 - 코드가 길어서 메서드로 뺌)
    private void sendPushNotification(MessageSentEvent event, ChatMessageResponse message, ChatRoomSummaryResponse commonSummary) {
        String contentSnippet = message.originContent();
        if (message.messageType() == MessageType.IMAGE) {
            contentSnippet = "send picture";
        } else if (message.messageType() == MessageType.VIDEO) {
            contentSnippet = "send video.";
        }
        String roomName = (commonSummary != null) ? commonSummary.roomName() : "Chat Room";

        List<Long> notificationTargets = event.recipientIds().stream()
                .filter(id -> !id.equals(message.senderId()))
                .toList();

        if (!notificationTargets.isEmpty()) {
            NotificationBulkEvent bulkEvent = new NotificationBulkEvent(
                    notificationTargets,
                    message.senderId(),
                    NotificationType.chat,
                    message.roomId(),
                    contentSnippet,
                    roomName
            );
            eventPublisher.publishEvent(bulkEvent);
        }
    }
}
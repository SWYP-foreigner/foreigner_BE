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

    private final FastSocketSender fastSocketSender;
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
        sendPushNotification(event, message, commonSummary);
        List<Long> recipients = event.recipientIds();
        if (recipients == null || recipients.isEmpty()) return;

        String messageSuffix = "/" + message.roomId() + "/messages";
        TypedWebSocketResponse<ChatMessageResponse> messagePayload =
                new TypedWebSocketResponse<>("NEW_MESSAGE", message);
        fastSocketSender.sendToUsersFast(recipients, messageSuffix, messagePayload);

        if (commonSummary != null) {
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
package core.domain.chat.service;

import core.domain.chat.dto.*;
import core.domain.notification.dto.NotificationBulkEvent;
import core.global.enums.MessageType;
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

    private final SimpMessagingTemplate messagingTemplate;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * [메시지 전송 이벤트]
     * - DB 커밋 후 실행 (AFTER_COMMIT) -> 전송 데이터 신뢰성 보장
     * - 구버전 호환을 위해 TypedWebSocketResponse(@JsonUnwrapped) 사용
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMessageSent(MessageSentEvent event) {
        ChatMessageResponse message = event.messageResponse();
        ChatRoomSummaryResponse commonSummary = event.roomSummary();

        // 1. 알림(Notification) 전송 로직
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

        // 2. 웹소켓 전송 (병렬 처리)
        event.recipientIds().parallelStream().forEach(recipientId -> {
            // A. 채팅방 내부 메시지 전송 (NEW_MESSAGE)
            String messageDestination = String.format("/topic/user/%s/%s/messages", recipientId, message.roomId());
            messagingTemplate.convertAndSend(
                    messageDestination,
                    new TypedWebSocketResponse<>("NEW_MESSAGE", message)
            );

            // B. 채팅방 목록 갱신 (ROOM_UPDATE) - 더미 카운트 사용 최적화
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

                messagingTemplate.convertAndSend(
                        "/topic/user/" + recipientId + "/rooms",
                        new TypedWebSocketResponse<>("ROOM_UPDATE", fastSummary)
                );
            }
        });
    }


    /**
     * [메시지 읽음 처리 이벤트]
     * - 안정성을 위해 TransactionalEventListener(AFTER_COMMIT) 권장
     * - 타입 명시: READ_COUNT_UPDATE, ROOM_UPDATE
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMessageRead(MessageReadEvent event) {
        // 1. 말풍선 옆 숫자 갱신 (채팅방 내부)
        if (!event.updatedReadCounts().isEmpty()) {
            messagingTemplate.convertAndSend(
                    "/topic/rooms/" + event.roomId() + "/read-counts",
                    new TypedWebSocketResponse<>(
                            "READ_COUNT_UPDATE",
                            new MessageReadCountUpdateResponse(event.updatedReadCounts())
                    )
            );
        }

        // 2. 채팅방 목록의 빨간 배지 갱신 (채팅방 목록)
        if (event.roomSummary() != null) {
            messagingTemplate.convertAndSend(
                    "/topic/user/" + event.readerId() + "/rooms",
                    new TypedWebSocketResponse<>("ROOM_UPDATE", event.roomSummary())
            );
        }
    }

    /**
     * [메시지 삭제 이벤트]
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
}
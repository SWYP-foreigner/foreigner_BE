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

    private final SimpMessagingTemplate messagingTemplate;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * [메시지 전송 이벤트] - 여기가 핵심 최적화 대상입니다.
     * DB 커밋 후 실행 (AFTER_COMMIT)
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMessageSent(MessageSentEvent event) {
        ChatMessageResponse message = event.baseResponse();

        Map<String, List<Long>> recipientsByLang = event.recipientsByLang();
        ChatRoomSummaryResponse commonSummary = event.roomSummary();
        sendPushNotification(event, message, commonSummary);
        List<Long> recipients = recipientsByLang.values()
                .stream()
                .flatMap(List::stream)
                .toList();
        if (recipients.isEmpty()) return;

        // =================================================================
        // [변경] 웹소켓 전송 (SimpMessagingTemplate 루프 방식)
        // =================================================================

        // A. 채팅방 내부 메시지 전송 (NEW_MESSAGE)
        TypedWebSocketResponse<ChatMessageResponse> messagePayload =
                new TypedWebSocketResponse<>("NEW_MESSAGE", message);
        int sendCount = 0;
        for (Long userId : recipients) {
            String destination = "/topic/user/" + userId + "/" + message.roomId() + "/messages";
            messagingTemplate.convertAndSend(destination, messagePayload);
            sendCount++;
        }
        log.info("[SEND_DONE] roomId={}, totalSend={}", message.roomId(), sendCount);
        // B. 채팅방 목록 갱신 (ROOM_UPDATE)
        if (commonSummary != null) {
            TypedWebSocketResponse<ChatRoomSummaryResponse> roomPayload =
                    new TypedWebSocketResponse<>("ROOM_UPDATE", commonSummary);

            for (Long userId : recipients) {
                String destination = "/topic/user/" + userId + "/rooms";
                messagingTemplate.convertAndSend(destination, roomPayload);
            }
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

        List<Long> notificationTargets = event.recipientsByLang().values().stream()
                .flatMap(List::stream)
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
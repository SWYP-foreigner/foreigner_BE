package core.domain.chat.service;

import core.domain.chat.dto.*;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.notification.dto.NotificationBulkEvent;
import core.domain.notification.dto.NotificationEvent;
import core.domain.notification.entity.Notification;
import core.global.enums.MessageType;
import core.global.enums.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatEventListener {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 메시지 전송 시 발생하는 이벤트 처리
     * 1. 채팅방 내부 메시지 전송 (/topic/user/{id}/{roomId}/messages)
     * 2. 채팅방 목록 갱신 (/topic/user/{id}/rooms) - UnreadCount 계산 포함
     */

    /**
     * 메시지 읽음 처리 이벤트
     */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * [핵심 최적화 적용]
     * 1. DB 조회(Count 쿼리) 제거 -> 더미 데이터(-1) 전송
     * 2. 병렬 스트림(parallelStream) 적용 -> 전송 속도 극대화
     * 3. 트랜잭션 커밋 후 실행 보장 (AFTER_COMMIT)
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMessageSent(MessageSentEvent event) {
        ChatMessageResponse message = event.messageResponse();
        ChatRoomSummaryResponse commonSummary = event.roomSummary();
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
        event.recipientIds().parallelStream().forEach(recipientId -> {
            String messageDestination = String.format("/topic/user/%s/%s/messages", recipientId, message.roomId());
            messagingTemplate.convertAndSend(messageDestination, message);
            if (commonSummary != null) {
                int dummyUnreadCount = -1;

                ChatRoomSummaryResponse fastSummary = new ChatRoomSummaryResponse(
                        commonSummary.roomId(),
                        commonSummary.roomName(),
                        commonSummary.lastMessageContent(),
                        commonSummary.lastMessageTime(),
                        commonSummary.roomImageUrl(),
                        dummyUnreadCount,
                        commonSummary.participantCount()
                );
                messagingTemplate.convertAndSend("/topic/user/" + recipientId + "/rooms", fastSummary);
            }

        });
    }


    @EventListener
    @Async
    public void handleMessageRead(MessageReadEvent event) {
        if (!event.updatedReadCounts().isEmpty()) {
            messagingTemplate.convertAndSend(
                    "/topic/rooms/" + event.roomId() + "/read-counts",
                    new MessageReadCountUpdateResponse(event.updatedReadCounts())
            );
        }
        if (event.roomSummary() != null) {
            messagingTemplate.convertAndSend(
                    "/topic/user/" + event.readerId() + "/rooms",
                    event.roomSummary()
            );
        }
    }

    /**
     * 메시지 삭제 이벤트
     */
    @EventListener
    @Async
    public void handleMessageDeleted(MessageDeletedEvent event) {
        Map<String, String> payload = Map.of(
                "id", event.messageId().toString(),
                "type", "delete"
        );
        messagingTemplate.convertAndSend("/topic/rooms/" + event.roomId(), payload);
        log.info("Broadcasted delete event for message {} in room {}", event.messageId(), event.roomId());
    }


}
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
    private final ChatMessageRepository chatMessageRepository;
    private final ChatParticipantRepository chatParticipantRepository;

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

        // -----------------------------------------------------------------------
        // ⚡ [최적화 1] 알림 내용 생성 및 대상자 필터링 (Loop 밖에서 1회 수행)
        // -----------------------------------------------------------------------
        String contentSnippet = message.originContent();
        if (message.messageType() == MessageType.IMAGE) {
            contentSnippet = "send picture";
        } else if (message.messageType() == MessageType.VIDEO) {
            contentSnippet = "send video.";
        }
        String roomName = (commonSummary != null) ? commonSummary.roomName() : "Chat Room";

        // 본인을 제외한 알림 대상자 리스트 추출
        List<Long> notificationTargets = event.recipientIds().stream()
                .filter(id -> !id.equals(message.senderId()))
                .toList();

        // -----------------------------------------------------------------------
        // ⚡ [최적화 2] 알림 이벤트 'Bulk' 발행 (단 1회 호출)
        // -----------------------------------------------------------------------
        if (!notificationTargets.isEmpty()) {
            NotificationBulkEvent bulkEvent = new NotificationBulkEvent(
                    notificationTargets, // 478명의 ID가 담긴 리스트
                    message.senderId(),
                    NotificationType.chat,
                    message.roomId(),
                    contentSnippet,
                    roomName
            );

            eventPublisher.publishEvent(bulkEvent);
        }

        // -----------------------------------------------------------------------
        // ⚡ [기존 유지] 웹소켓 패킷 발송 (병렬 처리)
        // -----------------------------------------------------------------------
        // 알림은 위에서 처리했으므로, 여기서는 순수하게 WebSocket 전송만 집중합니다.
        event.recipientIds().parallelStream().forEach(recipientId -> {

            // 1. [채팅방 내부] 실시간 메시지 전송
            String messageDestination = String.format("/topic/user/%s/%s/messages", recipientId, message.roomId());
            messagingTemplate.convertAndSend(messageDestination, message);

            // 2. [채팅방 목록] 갱신 (DB 조회 X, 더미 데이터 전송)
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

        // -----------------------------------------------------------------------
        // 📊 [로그 유지]
        // -----------------------------------------------------------------------
        long currentTime = System.currentTimeMillis();
        long totalDuration = currentTime - event.startTime();

        log.info("🚀 [E2E Performance] Message {} broadcast complete.", message.id());
        log.info("   - Recipients: {} users", event.recipientIds().size());
        log.info("   - Notification Targets: {} users (Bulk Published Once)", notificationTargets.size());
        log.info("   - Total E2E Latency: {} ms (Service + DB Commit + Async Wait + Socket Push)", totalDuration);
        log.info("Message {} broadcasted via Parallel Stream to {} recipients (DB Query Skipped)",
                message.id(), event.recipientIds().size());
    }


    @EventListener
    @Async
    public void handleMessageRead(MessageReadEvent event) {
        // 1. 채팅방 내부: 읽음 숫자(1) 갱신
        if (!event.updatedReadCounts().isEmpty()) {
            messagingTemplate.convertAndSend(
                    "/topic/rooms/" + event.roomId() + "/read-counts",
                    new MessageReadCountUpdateResponse(event.updatedReadCounts())
            );
        }

        // 2. 채팅방 목록: 읽은 사람 본인의 목록 갱신 (UnreadCount 0 등)
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

    // --- Private Helper Method ---

    /**
     * DB에서 특정 유저의 안 읽은 메시지 개수 조회
     */
    private int calculateUnreadCount(Long roomId, Long userId) {
        // 1. 참여자의 마지막 읽은 메시지 ID 확인 (없으면 0)
        Long lastReadId = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .map(p -> p.getLastReadMessageId() == null ? 0L : p.getLastReadMessageId())
                .orElse(0L);

        // 2. 해당 ID보다 뒤에 온 메시지 개수 카운트
        return chatMessageRepository.countUnreadMessages(roomId, lastReadId, userId);
    }
}
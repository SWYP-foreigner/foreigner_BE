package core.domain.chat.service;

import core.domain.chat.dto.*;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.notification.dto.NotificationEvent;
import core.global.enums.NotificationType;
import core.global.enums.chat.MessageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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

        // ⚡ [최적화] CPU를 풀가동하여 동시에 웹소켓 패킷 발송 (순서 상관없음)
        event.recipientIds().parallelStream().forEach(recipientId -> {

            // 1. [채팅방 내부] 실시간 메시지 전송
            String messageDestination = String.format("/topic/user/%s/%s/messages", recipientId, message.roomId());
            messagingTemplate.convertAndSend(messageDestination, message);

            // 2. [채팅방 목록] 갱신 (DB 조회 제거됨)
            if (commonSummary != null) {
                // 💡 [핵심] 여기서 DB를 조회하지 않고 고정값(-1)을 보냅니다.
                // 클라이언트는 -1이 오면 "안 읽은 개수는 건드리지 말고(혹은 +1 하고), 내용과 시간만 갱신하자"라고 판단해야 함.
                int dummyUnreadCount = -1;

                ChatRoomSummaryResponse fastSummary = new ChatRoomSummaryResponse(
                        commonSummary.roomId(),
                        commonSummary.roomName(),
                        commonSummary.lastMessageContent(),
                        commonSummary.lastMessageTime(),
                        commonSummary.roomImageUrl(),
                        dummyUnreadCount, // 👈 여기가 포인트! (DB 조회 X)
                        commonSummary.participantCount()
                );
                messagingTemplate.convertAndSend("/topic/user/" + recipientId + "/rooms", fastSummary);
            }

            // 3. [알림] 시스템 연동 (본인이 아닌 경우에만)
            if (!recipientId.equals(message.senderId())) {
                String contentSnippet = message.originContent(); // 필드명 주의 (originContent)

                if (message.messageType() == MessageType.IMAGE) {
                    contentSnippet = "사진을 보냈습니다.";
                } else if (message.messageType() == MessageType.VIDEO) {
                    contentSnippet = "동영상을 보냈습니다.";
                }

                String roomName = (commonSummary != null) ? commonSummary.roomName() : "Chat Room";

                NotificationEvent notificationEvent = new NotificationEvent(
                        recipientId,
                        message.senderId(),
                        NotificationType.chat,
                        message.roomId(),
                        contentSnippet,
                        roomName
                );

                // 알림 리스너에게 토스 (비동기)
                eventPublisher.publishEvent(notificationEvent);
            }
        });

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
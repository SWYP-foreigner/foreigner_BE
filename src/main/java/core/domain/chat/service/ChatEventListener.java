package core.domain.chat.service;

import core.domain.chat.dto.*;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.notification.dto.NotificationEvent;
import core.global.enums.MessageType;
import core.global.enums.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.context.ApplicationEventPublisher;
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

    @EventListener
    @Async
    public void handleMessageSent(MessageSentEvent event) {
        ChatMessageResponse message = event.messageResponse();
        ChatRoomSummaryResponse commonSummary = event.roomSummary();

        for (Long recipientId : event.recipientIds()) {
            String messageDestination = String.format("/topic/user/%s/%s/messages", recipientId, message.roomId());
            messagingTemplate.convertAndSend(messageDestination, message);
            if (commonSummary != null) {
                int personalUnreadCount = calculateUnreadCount(message.roomId(), recipientId);
                ChatRoomSummaryResponse personalSummary = new ChatRoomSummaryResponse(
                        commonSummary.chatRoomId(),
                        commonSummary.roomName(),
                        commonSummary.lastMessageContent(),
                        commonSummary.lastMessageTime(),
                        commonSummary.roomImageUrl(),
                        personalUnreadCount,
                        commonSummary.participantCount()
                );
                messagingTemplate.convertAndSend("/topic/user/" + recipientId + "/rooms", personalSummary);
            }

            if (!recipientId.equals(message.senderId())) {
                String contentSnippet = message.originContent();

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

                eventPublisher.publishEvent(notificationEvent);
            }
        }
        log.info("Message {} processed & broadcasted to {} recipients", message.id(), event.recipientIds().size());
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
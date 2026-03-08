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
        ChatMessageResponse baseResponse = event.baseResponse();
        Map<String, List<Long>> recipientsByLang = event.recipientsByLang();
        Map<String, String> translations = event.translations();

        // 1. [Push] 나를 제외한 모든 참여자에게 알림 발송
        if (event.pushTargetIds() != null && !event.pushTargetIds().isEmpty()) {
            sendPushNotification(event.pushTargetIds(), baseResponse, event.roomSummary());
        }

        if (recipientsByLang == null || recipientsByLang.isEmpty()) return;

        // 2. [Websocket] 언어별 Zero-Copy 발송 엔진
        recipientsByLang.forEach((lang, recipients) -> {
            if (recipients == null || recipients.isEmpty()) return;

            // 언어에 따른 번역본 결정
            String targetContent = (!"NONE".equals(lang) && !"SELF".equals(lang))
                    ? translations.get(lang) : null;

            // 클라이언트 협의 DTO (원문 + 번역본)
            ChatMessageResponse personalizedMsg = new ChatMessageResponse(
                    baseResponse.id(), baseResponse.roomId(), baseResponse.senderId(),
                    baseResponse.originContent(), targetContent,
                    baseResponse.sentAt(), baseResponse.senderFirstName(),
                    baseResponse.senderLastName(), baseResponse.senderImageUrl(),
                    baseResponse.messageType(), baseResponse.mediaUrl(), baseResponse.thumbnailUrl()
            );

            TypedWebSocketResponse<ChatMessageResponse> payload =
                    new TypedWebSocketResponse<>("NEW_MESSAGE", personalizedMsg);

            String destination = "/" + baseResponse.roomId() + "/messages";
            fastSocketSender.sendToUsersFast(recipients, destination, payload);
        });

        // 3. [Room Update] 온라인 유저들에게 방 목록 갱신
        if (event.roomSummary() != null) {
            List<Long> allOnlineIds = recipientsByLang.values().stream()
                    .flatMap(List::stream).toList();

            fastSocketSender.sendToUsersFast(allOnlineIds, "/rooms",
                    new TypedWebSocketResponse<>("ROOM_UPDATE", event.roomSummary()));
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


    private void sendPushNotification(List<Long> pushTargetIds, ChatMessageResponse message, ChatRoomSummaryResponse commonSummary) {
        // 1. 전송 대상이 없으면 바로 종료
        if (pushTargetIds == null || pushTargetIds.isEmpty()) return;

        // 2. 메시지 타입에 따른 요약 문구 생성
        String contentSnippet = message.originContent();
        if (message.messageType() == MessageType.IMAGE) {
            contentSnippet = "sent a picture.";
        } else if (message.messageType() == MessageType.VIDEO) {
            contentSnippet = "sent a video.";
        }

        String roomName = (commonSummary != null) ? commonSummary.roomName() : "Chat Room";

        // 3. 알림 이벤트 발행 (Notification 서비스가 처리하도록)
        // 서비스에서 이미 본인(senderId)은 필터링해서 보냈으므로 여기서 추가 필터링은 필요 없음
        NotificationBulkEvent bulkEvent = new NotificationBulkEvent(
                pushTargetIds,
                message.senderId(),
                NotificationType.chat,
                message.roomId(),
                contentSnippet,
                roomName
        );

        eventPublisher.publishEvent(bulkEvent);
    }
}
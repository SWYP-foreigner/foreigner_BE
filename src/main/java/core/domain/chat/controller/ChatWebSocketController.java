package core.domain.chat.controller;

import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController; // 제거

@Controller
@RequiredArgsConstructor
public class ChatWebSocketController {

    private final ChatService chatService;
    private final SimpMessagingTemplate messagingTemplate;
    private final SimpMessageSendingOperations template;
    private final Logger log = LoggerFactory.getLogger(ChatWebSocketController.class);

    /**
     * @apiNote 새로운 메시지를 전송하고, 해당 채팅방의 구독자들에게 브로드캐스트합니다.
     *
     * @param req 전송 메시지 요청 (roomId, senderId, content)
     */
    @MessageMapping("/chat.sendMessage")
    public void sendMessage(SendMessageRequest req) {
        try {
            ChatMessage saved = chatService.saveMessage(req.roomId(), req.senderId(), req.content());

            ChatMessageResponse response = new ChatMessageResponse(
                    saved.getId(),
                    saved.getChatRoom().getId(),
                    saved.getSender().getId(),
                    saved.getContent(),
                    saved.getSentAt()
            );

            messagingTemplate.convertAndSend(
                    "/topic/rooms/" + req.roomId(),
                    response
            );

            log.info("메시지 전송 성공: roomId={}, senderId={}", req.roomId(), req.senderId());
        } catch (Exception e) {
            log.error("메시지 전송 실패", e);
        }
    }

    /**
     * @apiNote 사용자가 메시지를 입력 중임을 알리는 이벤트를 다른 참여자에게 전송합니다.
     *
     * @param event 타이핑 이벤트 정보 (roomId, userId, isTyping)
     */
    @MessageMapping("/chat.typing")
    public void handleTypingEvent(@Payload TypingEvent event) {
        template.convertAndSend("/topic/chatrooms/" + event.roomId(), event);
    }

    /**
     * @apiNote 메시지 읽음 상태를 업데이트하고, 해당 채팅방의 다른 참여자에게 실시간으로 알립니다.
     * 1:1 채팅의 경우 상대방이 읽으면 readCount가 줄고, 그룹 채팅은 읽지 않은 사람 수가 줄어듭니다.
     *
     * @param req 읽음 상태 업데이트 요청 (roomId, readerId, lastReadMessageId)
     */
    @MessageMapping("/chat.markAsRead")
    public void markMessagesAsRead(@Payload MarkAsReadRequest req) {
        try {
            chatService.markMessagesAsRead(req.roomId(), req.readerId(), req.lastReadMessageId());

            messagingTemplate.convertAndSend(
                    "/topic/rooms/" + req.roomId() + "/read-status",
                    new ReadStatusResponse(req.roomId(), req.readerId(), req.lastReadMessageId())
            );

            log.info("메시지 읽음 처리 성공: roomId={}, readerId={}, lastReadMessageId={}",
                    req.roomId(), req.readerId(), req.lastReadMessageId());
        } catch (Exception e) {
            log.error("메시지 읽음 처리 실패", e);
        }
    }
}
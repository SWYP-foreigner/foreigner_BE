package core.domain.chat.controller;

import core.domain.chat.dto.ChatMessageResponse; // DTO 추가
import core.domain.chat.dto.SendMessageRequest;
import core.domain.chat.dto.TypingEvent;
import core.domain.chat.entity.ChatMessage; // 엔티티 추가
import core.domain.chat.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class ChatWebSocketController {

    private final ChatService chatService;
    private final SimpMessagingTemplate messagingTemplate;
    private final SimpMessageSendingOperations template;
    private final Logger log = LoggerFactory.getLogger(ChatWebSocketController.class);

    // 클라이언트에서 /app/chat.sendMessage 로 전송
    @MessageMapping("/chat.sendMessage")
    public void sendMessage(SendMessageRequest req) {
        try {
            // ChatService.saveMessage()가 ChatMessage 엔티티를 반환하도록 가정
            ChatMessage saved = chatService.saveMessage(req.roomId(), req.senderId(), req.content());

            // 클라이언트에 전송할 DTO로 변환
            ChatMessageResponse response = new ChatMessageResponse(
                    saved.getId(),
                    saved.getChatRoom().getId(),
                    saved.getSender().getId(),
                    saved.getContent(),
                    saved.getSentAt()
            );

            // 저장 성공 시 해당 방 구독자에게 DTO를 브로드캐스트
            messagingTemplate.convertAndSend(
                    "/topic/rooms/" + req.roomId(),
                    response
            );

            log.info("메시지 전송 성공: roomId={}, senderId={}", req.roomId(), req.senderId());
        } catch (Exception e) {
            log.error("메시지 전송 실패", e);
        }
    }
    @MessageMapping("/chat.typing")
    public void handleTypingEvent(@Payload TypingEvent event) {
        template.convertAndSend("/topic/chatrooms/" + event.roomId(), event);
    }
}
package core.domain.chat.controller;


import core.domain.chat.dto.ChatMessageDoc;
import core.domain.chat.dto.SendMessageRequest;
import core.domain.chat.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class ChatWebSocketController {

    private final ChatService chatService;
    private final SimpMessagingTemplate messagingTemplate;
    private final Logger log = LoggerFactory.getLogger(ChatWebSocketController.class);

    // 클라이언트에서 /app/chat.sendMessage 로 전송
    @MessageMapping("/chat.sendMessage")
    public void sendMessage(SendMessageRequest req) {
        try {
            ChatMessageDoc saved = chatService.saveMessage(req.roomId(), req.senderId(), req.content());

            // 저장 성공 시 해당 방 구독자에게 메시지 브로드캐스트
            messagingTemplate.convertAndSend(
                    "/topic/rooms/" + req.roomId(),
                    saved
            );

            log.info("메시지 전송: roomId={}, senderId={}", req.roomId(), req.senderId());
        } catch (Exception e) {
            log.error("메시지 전송 실패", e);
        }
    }
}

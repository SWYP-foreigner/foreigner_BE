package core.domain.chat.controller;

import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.service.ChatService;
import core.domain.chat.service.TranslationService;
import core.domain.user.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController; // 제거

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class ChatWebSocketController {

    private final ChatService chatService;
    private final SimpMessagingTemplate messagingTemplate;
    private final SimpMessageSendingOperations template;
    private final Logger log = LoggerFactory.getLogger(ChatWebSocketController.class);

    // 추가된 의존성
    private final UserRepository userRepository;
    private final TranslationService translationService;


    /**
     * @apiNote 새로운 메시지를 전송하고, 해당 채팅방의 구독자들에게 브로드캐스트합니다.
     *
     * @param req 전송 메시지 요청 (roomId, senderId, content, targetLanguage, translate)
     */
    @MessageMapping("/chat.sendMessage")
    public void sendMessage(SendMessageRequest req) {
        try {
            ChatMessage saved = chatService.saveMessage(req.roomId(), req.senderId(), req.content());

            String broadcastContent = saved.getContent();
            String originalContent = null;

            if (req.translate() && req.targetLanguage() != null && !req.targetLanguage().isEmpty()) {
                originalContent = saved.getContent();
                List<String> translatedList = translationService.translateMessages(List.of(originalContent), req.targetLanguage());
                if (!translatedList.isEmpty()) {
                    broadcastContent = translatedList.get(0);
                }
            }

            ChatMessageResponse response = new ChatMessageResponse(
                    saved.getId(),
                    saved.getChatRoom().getId(),
                    saved.getSender().getId(),
                    broadcastContent,
                    saved.getSentAt(),
                    originalContent
            );

            messagingTemplate.convertAndSend("/topic/rooms/" + req.roomId(), response);
            LocalDateTime sentAt = saved.getSentAt().atZone(ZoneId.systemDefault()).toLocalDateTime();
            chatService.getParticipants(req.roomId()).forEach(user -> {
                int unreadCount = chatService.countUnreadMessages(req.roomId(), user.getId());
                ChatRoomSummaryResponse summary = new ChatRoomSummaryResponse(
                        saved.getChatRoom().getId(),
                        saved.getContent(),
                        sentAt,
                        unreadCount
                );
                messagingTemplate.convertAndSend("/topic/user/" + user.getId() + "/rooms", summary);
            });

            log.info("메시지 전송 성공: roomId={}, senderId={}", req.roomId(), req.senderId());
        } catch (Exception e) {
            log.error("메시지 전송 실패", e);
        }
    }

    // 기존의 나머지 메서드들은 그대로 유지됩니다.
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

            // 기존 읽음 상태 알림
            messagingTemplate.convertAndSend(
                    "/topic/rooms/" + req.roomId() + "/read-status",
                    new ReadStatusResponse(req.roomId(), req.readerId(), req.lastReadMessageId())
            );

            // 추가: 채팅방 리스트 갱신 (읽음 반영된 unreadCount)
            int unreadCount = chatService.countUnreadMessages(req.roomId(), req.readerId());
            ChatRoomSummaryResponse summary = new ChatRoomSummaryResponse(
                    req.roomId(),
                    chatService.getLastMessageContent(req.roomId()),
                    chatService.getLastMessageTime(req.roomId()),
                    unreadCount
            );

            messagingTemplate.convertAndSend("/topic/user/" + req.readerId() + "/rooms", summary);

            log.info("메시지 읽음 처리 성공: roomId={}, readerId={}, lastReadMessageId={}",
                    req.roomId(), req.readerId(), req.lastReadMessageId());
        } catch (Exception e) {
            log.error("메시지 읽음 처리 실패", e);
        }
    }

    @Operation(summary = "메시지 전송", description = "STOMP 실제 엔드포인트: /app/chat.sendMessage\n구독 채널: /topic/rooms/{roomId}")
    @GetMapping("/docs/chat/sendMessage")
    public SendMessageRequest sendMessageExample() {
        return new SendMessageRequest(1L, 2L, "안녕하세요", false);
    }

   @Operation(summary = "메시지 전송 예시 (번역 포함)",
              description = "STOMP 실제 엔드포인트: /app/chat.sendMessage\n" +
                      "구독 채널: /topic/rooms/{roomId}\n" +
                      "메시지를 한국어로 번역 요청하는 예시입니다.")
    @GetMapping("/docs/chat/sendMessage-with-translation")
    public SendMessageRequest sendMessageWithTranslationExample() {
        return new SendMessageRequest(1L, 2L, "Hello, how are you?", "ko", true);
    }
    @Operation(summary = "타이핑 이벤트", description = "STOMP 실제 엔드포인트: /app/chat.typing\n구독 채널: /topic/chatrooms/{roomId}")
    @GetMapping("/docs/chat/typing")
    public TypingEvent typingExample() {
        return new TypingEvent(1L, "이용준", 3L,true);
    }

    @Operation(summary = "메시지 읽음 처리", description = "STOMP 실제  엔드포인트: /app/chat.markAsRead\n구독 채널: /topic/rooms/{roomId}/read-status")
    @GetMapping("/docs/chat/markAsRead")
    public MarkAsReadRequest readExample() {
        return new MarkAsReadRequest(1L, 2L, 99L);
    }
}
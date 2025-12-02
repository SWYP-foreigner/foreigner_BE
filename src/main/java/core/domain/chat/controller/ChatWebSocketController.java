package core.domain.chat.controller;

import core.domain.chat.dto.*;
import core.domain.chat.service.ChatMessageService;
import core.global.config.CustomUserDetails;
import core.global.docs.annotations.ChatErrorDocs;
import core.global.docs.annotations.CommonErrorCodeDocs;
import core.global.docs.annotations.UserErrorDocs;
import core.global.enums.errorcode.ChatErrorCode;
import core.global.enums.errorcode.CommonErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;


@Controller
@RequiredArgsConstructor
public class ChatWebSocketController {

    private final ChatMessageService chatService;
    private final Logger log = LoggerFactory.getLogger(ChatWebSocketController.class);


    /**
     * @apiNote 새로운 메시지를 전송하고, 해당 채팅방의 구독자들에게 브로드캐스트합니다.
     *
     * @param req 전송 메시지 요청 (roomId, senderId, content, targetLanguage, translate)
     */
    @MessageMapping("/chat.sendMessage")
    @ChatErrorDocs({ChatErrorCode.DUPLICATE_REPORT, ChatErrorCode.MESSAGE_NOT_FOUND, ChatErrorCode.CANNOT_REPORT_SELF })
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    @CommonErrorCodeDocs({CommonErrorCode.TRANSLATE_FAIL})
    public void sendMessage(
            @Payload SendMessageRequest req,  @AuthenticationPrincipal CustomUserDetails principal
    ) {
        log.info(String.valueOf(principal.getUserId()));
        try {
            chatService.processAndSendChatMessage(req);
        } catch (Exception e) {
            log.error("메시지 전송 실패", e);
        }
    }


    /**
     * @apiNote 메시지 읽음 상태를 업데이트하고, 해당 채팅방의 다른 참여자에게 실시간으로 알립니다.
     * 1:1 채팅의 경우 상대방이 읽으면 readCount가 줄고, 그룹 채팅은 읽지 않은 사람 수가 줄어듭니다.
     *
     * @param req 읽음 상태 업데이트 요청 (roomId, readerId, lastReadMessageId)
     */

    @MessageMapping("/chat.markAsRead")
    @ChatErrorDocs({ChatErrorCode.CHAT_ROOM_NOT_FOUND})
    public void markMessagesAsRead(@Payload MarkAsReadRequest req) {
        try {
            chatService.processMarkAsRead(req, req.userId());
        } catch (Exception e) {
            log.error("메시지 읽음 처리 중 오류 발생: {}", req, e);
        }
    }
    /**
     * @apiNote 메시지 삭제를 처리하고, 해당 채팅방의 모든 참여자에게 삭제 사실을 알립니다.
     *
     * @param req 삭제 요청 정보 (messageId, userId)
     */
    @MessageMapping("/chat.deleteMessage")
    @ChatErrorDocs({ChatErrorCode.MESSAGE_NOT_FOUND, ChatErrorCode.FORBIDDEN_MESSAGE_DELETE})
    public void deleteMessage(@Payload DeleteMessageRequest req) {
        try {
            chatService.deleteMessageAndBroadcast(req.messageId(), req.senderId());
            log.info("메시지 삭제 요청 처리: messageId={}, userId={}", req.messageId(), req.senderId());
        } catch (Exception e) {
            log.error("메시지 삭제 처리 중 에러 발생", e);
        }
    }
    @MessageMapping("/chat.sendMedia")
    @ChatErrorDocs({ChatErrorCode.CHAT_ROOM_NOT_FOUND})
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    public void sendMediaMessage(SendMediaMessageRequest req) {
        chatService.processAndSendMediaMessage(req);
    }
}
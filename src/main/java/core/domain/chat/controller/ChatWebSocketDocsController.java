package core.domain.chat.controller;

import core.domain.chat.dto.*;
import core.global.docs.annotations.ChatErrorDocs;
import core.global.docs.annotations.CommonErrorCodeDocs;
import core.global.docs.annotations.GlobalErrorDocs;
import core.global.docs.annotations.UserErrorDocs;
import core.global.enums.errorcode.ChatErrorCode;
import core.global.enums.errorcode.CommonErrorCode;
import core.global.enums.errorcode.GlobalErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 이 컨트롤러는 WebSocket(STOMP) API를 Swagger UI에 노출하기 위한 문서 전용 컨트롤러입니다.
 * 실제 HTTP 요청을 처리하지 않으며, 문서 자동 생성 목적으로만 사용됩니다.
 */
@Tag(name = "채팅 전송 (WebSocket)", description = "STOMP 프로토콜을 이용한 실시간 채팅 API (Destination Prefix: /pub)")
@RestController
@RequestMapping("/api/v1/docs/ws") // 실제 호출을 방지하기 위한 가짜 경로
@GlobalErrorDocs({GlobalErrorCode.INTERNAL_SERVER_ERROR, GlobalErrorCode.INVALID_INPUT})
public class ChatWebSocketDocsController {

    @Operation(
            summary = "텍스트 메시지 전송 [WS]",
            description = "STOMP 전송: `/pub/chat.sendMessage` \n\n새로운 메시지를 전송하고 구독자들에게 브로드캐스트합니다."
    )
    @ApiResponses({@ApiResponse(responseCode = "200", description = "메시지 전송 성공")})
    @PostMapping("/chat.sendMessage")
    @ChatErrorDocs({ChatErrorCode.DUPLICATE_REPORT, ChatErrorCode.MESSAGE_NOT_FOUND, ChatErrorCode.CANNOT_REPORT_SELF })
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    @CommonErrorCodeDocs({CommonErrorCode.TRANSLATE_FAIL})
    public void sendMessage(@RequestBody SendMessageRequest req) {
        throw new UnsupportedOperationException("This is for documentation only.");
    }

    @Operation(
            summary = "메시지 읽음 상태 업데이트 [WS]",
            description = "STOMP 전송: `/pub/chat.markAsRead` \n\n특정 메시지까지의 읽음 상태를 업데이트합니다."
    )
    @ApiResponses({@ApiResponse(responseCode = "200", description = "읽음 처리 성공")})
    @PostMapping("/chat.markAsRead")
    @ChatErrorDocs({ChatErrorCode.CHAT_ROOM_NOT_FOUND})
    public void markMessagesAsRead(@RequestBody MarkAsReadRequest req) {
        throw new UnsupportedOperationException("This is for documentation only.");
    }

    @Operation(
            summary = "메시지 삭제 [WS]",
            description = "STOMP 전송: `/pub/chat.deleteMessage` \n\n메시지를 삭제하고 삭제 이벤트를 알립니다."
    )
    @ApiResponses({@ApiResponse(responseCode = "200", description = "삭제 성공")})
    @PostMapping("/chat.deleteMessage")
    @ChatErrorDocs({ChatErrorCode.MESSAGE_NOT_FOUND, ChatErrorCode.FORBIDDEN_MESSAGE_DELETE})
    public void deleteMessage(@RequestBody DeleteMessageRequest req) {
        throw new UnsupportedOperationException("This is for documentation only.");
    }

    @Operation(
            summary = "미디어 메시지 전송 [WS]",
            description = "STOMP 전송: `/pub/chat.sendMedia` \n\nS3 업로드 완료 후 파일 Key를 포함하여 메시지를 전송합니다."
    )
    @ApiResponses({@ApiResponse(responseCode = "200", description = "미디어 전송 성공")})
    @PostMapping("/chat.sendMedia")
    @ChatErrorDocs({ChatErrorCode.CHAT_ROOM_NOT_FOUND})
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    public void sendMediaMessage(@RequestBody SendMediaMessageRequest req) {
        throw new UnsupportedOperationException("This is for documentation only.");
    }
}

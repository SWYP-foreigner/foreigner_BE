package core.domain.chat.controller;

import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.service.ChatService;
import core.domain.chat.service.ForbiddenWordService;
import core.global.dto.ApiResponse;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "채팅 API", description = "1:1 채팅, 그룹 채팅, 메시지 검색/삭제 등 채팅 기능 API")
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
    private final ChatService chatService;
    private final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final ForbiddenWordService forbiddenWordService;
    public ChatController(ChatService chatService, ForbiddenWordService forbiddenWordService) {
        this.chatService = chatService;
        this.forbiddenWordService = forbiddenWordService;
    }
    /**
     * 새로운 채팅방을 생성합니다.
     * 그룹 채팅, 1:1 채팅 모두 이 엔드포인트를 사용합니다.
     *
     * @param creatorId      채팅방을 생성하는 사용자의 ID.
     * TODO: 로그인 작업 완료시  JWT 토큰에서 유저ID를 추출해야 합니다.
     * @param participantIds 채팅방에 초대할 사용자 ID 목록.
     * @return 생성된 채팅방 정보를 담은 응답 (ChatRoomResponse).
     */

    @PostMapping("/rooms")
    public ResponseEntity<ApiResponse<ChatRoomResponse>> createRoom(
            Long creatorId, @RequestBody  List<Long> participantIds
    ) {
        ChatRoom room = chatService.createRoom(creatorId,participantIds);
        ChatRoomResponse response = ChatRoomResponse.from(room);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 현재 로그인된 사용자가 참여하고 있는 채팅방 목록을 조회합니다.
     *
     * @param userId 채팅방 목록을 조회할 사용자의 ID.
     * TODO: 로그인 작업 완료시  JWT 토큰에서 유저ID를 추출해야 합니다.
     * @return 사용자가 참여하고 있는 채팅방 목록을 담은 응답 (List<ChatRoomResponse>).
     */

    @Operation(summary = "자신의 채팅방 리스트 조회")
    @GetMapping("/rooms")
    public ResponseEntity<ApiResponse<List<ChatRoomResponse>>> getChatRooms( Long userId) {
        List<ChatRoom> rooms = chatService.getMyChatRooms(userId);
        List<ChatRoomResponse> responses = rooms.stream()
                .map(ChatRoomResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    /**
     * @apiNote 사용자가 채팅방을 나갑니다.
     * @param roomId 채팅방 ID
     * @return 성공 여부
     */
    @DeleteMapping("/rooms/{roomId}/leave")
    public ResponseEntity<ApiResponse<Void>> leaveChatRoom(@PathVariable Long roomId,@RequestParam Long userId) {
        chatService.leaveRoom(roomId, userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * @apiNote 커뮤니티로 만들어진 모임 채팅방은 활동이 끝나면 삭제를 원할 시 삭제.
     * @param roomId 삭제할 채팅방 ID
     * @return 성공 여부
     */
    @DeleteMapping("/rooms/{roomId}/admin") // 관리자용 엔드포인트
    public ResponseEntity<ApiResponse<Void>> deleteChatRoom(@PathVariable Long roomId) {
        chatService.forceDeleteRoom(roomId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "채팅방 메시지 조회")
    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<ApiResponse<List<ChatMessage>>> getMessages(@PathVariable Long roomId) {
        List<ChatMessage> messages = chatService.getMessages(roomId);
        if (messages == null) {
            log.warn("메시지 조회 실패: roomId={}", roomId);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail("메시지 조회에 실패했습니다."));
        }
        return ResponseEntity.ok(ApiResponse.success(messages));
    }

    @Operation(summary = "메시지 삭제")
    @DeleteMapping("/rooms/{roomId}/messages/{messageId}")
    public ResponseEntity<ApiResponse<Void>> deleteMessage(@PathVariable Long roomId, @PathVariable String messageId) {
        boolean deleted = chatService.deleteMessage(Long.valueOf(messageId));
        if (!deleted) {
            log.warn("메시지 삭제 실패: messageId={}", messageId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.fail("존재하지 않는 메시지입니다."));
        }
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "채팅 참여자 조회")
    @GetMapping("/rooms/{roomId}/participants")
    public ResponseEntity<ApiResponse<List<ChatParticipantResponse>>> getParticipants(@PathVariable Long roomId) {
        List<ChatParticipant> participants = chatService.getParticipants(roomId);
        if (participants == null) {
            log.warn("참여자 조회 실패: roomId={}", roomId);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail("참여자 조회에 실패했습니다."));
        }
        List<ChatParticipantResponse> responses = participants.stream()
                .map(p -> new ChatParticipantResponse(
                        p.getId(),
                        p.getUser().getId(),
                        p.getUser().getName(),
                        p.getJoinedAt(),
                        null,
                        p.getBlocked() != null && p.getBlocked(),
                        p.getDeleted() != null && p.getDeleted()
                )).collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @Operation(summary = "메시지 전송")
    @PostMapping("/rooms/{roomId}/messages")
    public ResponseEntity<ApiResponse<ChatMessage>> sendMessage(@PathVariable Long roomId, @RequestBody SendMessageRequest req) {
        try {
            // 1. 금칙어 검사
            if (forbiddenWordService.containsForbiddenWord(req.content())) {
                log.warn("금칙어 메시지 전송 시도: roomId={}, senderId={}", roomId, req.senderId());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(ApiResponse.fail("메시지에 금칙어가 포함되어 있습니다."));
            }

            ChatMessage savedMsg = chatService.saveMessage(roomId, req.senderId(), req.content());

            return ResponseEntity.ok(ApiResponse.success(savedMsg));

        } catch (IllegalArgumentException e) {
            log.warn("메시지 전송 실패: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.fail(e.getMessage()));
        } catch (Exception e) {
            log.error("메시지 저장 중 예외 발생: roomId={}, senderId={}", roomId, req.senderId(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail("메시지 저장에 실패했습니다."));
        }
    }
    @GetMapping("/rooms/{roomId}/messages/search")
    public ResponseEntity<List<ChatMessage>> searchMessages(@PathVariable Long roomId, @RequestParam String keyword) {
        List<ChatMessage> messages = chatService.searchMessages(roomId, keyword);
        return ResponseEntity.ok(messages);
    }

}

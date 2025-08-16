package core.domain.chat.controller;

import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.service.ChatService;
import core.domain.chat.service.ForbiddenWordService;
import core.global.dto.ApiResponse;
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

    @PostMapping("/rooms")
    public ResponseEntity<ApiResponse<ChatRoomResponse>> createRoom(
            @RequestParam boolean isGroup,
            @RequestBody List<ParticipantIdDto> participants
    ) {
        try {
            // ParticipantIdDto -> Long 변환
            List<Long> participantIds = participants.stream()
                    .map(ParticipantIdDto::getId)
                    .toList();

            ChatRoom room = chatService.createRoom(isGroup, participantIds);

            ChatRoomResponse response = new ChatRoomResponse(
                    room.getId(),
                    room.getGroup(),
                    room.getCreatedAt(),
                    room.getParticipants().stream()
                            .map(p -> new ChatParticipantResponse(
                                    p.getId(),
                                    p.getUser().getId(),
                                    p.getUser().getName(),
                                    p.getJoinedAt(),
                                    p.getLastReadMessageId(),
                                    p.getBlocked(),
                                    p.getDeleted()
                            ))
                            .toList()
            );

            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            log.error("채팅방 생성 예외", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail("채팅방 생성 중 예외가 발생했습니다."));
        }
    }



    @Operation(summary = "채팅방 리스트 조회")
    @GetMapping("/rooms")
    public ResponseEntity<ApiResponse<List<ChatRoomResponse>>> getChatRooms() {
        List<ChatRoom> rooms = chatService.getAllRooms();
        if (rooms == null) {
            log.warn("채팅방 목록 조회 실패: null 반환");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail("채팅방 목록 조회에 실패했습니다."));
        }
        List<ChatRoomResponse> responses = rooms.stream()
                .map(r -> new ChatRoomResponse(r.getId(), r.getGroup(), r.getCreatedAt(), List.of()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @Operation(summary = "채팅방 삭제")
    @DeleteMapping("/rooms/{roomId}")
    public ResponseEntity<ApiResponse<Void>> deleteChatRoom(@PathVariable Long roomId) {
        boolean deleted = chatService.deleteRoom(roomId);
        if (!deleted) {
            log.warn("채팅방 삭제 실패: roomId={}", roomId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.fail("존재하지 않는 채팅방입니다."));
        }
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "채팅방 메시지 조회 (Mongo)")
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

package core.domain.chat.controller;


import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.service.ChatService;
import core.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "채팅 API", description = "1:1 채팅, 그룹 채팅, 메시지 검색/삭제 등 채팅 기능 API")
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }


    @Operation(summary = "채팅방 생성")
    @PostMapping("/rooms")
    public ResponseEntity<ApiResponse<ChatRoomResponse>> createChatRoom(@RequestParam boolean isGroup) {
        ChatRoom room = chatService.createRoom(isGroup);
        ChatRoomResponse response = new ChatRoomResponse(
                room.getId(),
                room.getGroup(),
                room.getCreatedAt(),
                List.of()
        );
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "채팅방 리스트 조회")
    @GetMapping("/rooms")
    public ResponseEntity<ApiResponse<List<ChatRoomResponse>>> getChatRooms() {
        List<ChatRoom> rooms = chatService.getAllRooms();
        List<ChatRoomResponse> responses = rooms.stream()
                .map(r -> new ChatRoomResponse(r.getId(), r.getGroup(), r.getCreatedAt(), List.of()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @Operation(summary = "채팅방 삭제")
    @DeleteMapping("/rooms/{roomId}")
    public ResponseEntity<ApiResponse<Void>> deleteChatRoom(@PathVariable Long roomId) {
        chatService.deleteRoom(roomId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "채팅방 메시지 조회 (Mongo)")
    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<ApiResponse<List<ChatMessageDoc>>> getMessages(@PathVariable Long roomId) {
        List<ChatMessageDoc> messages = chatService.getMessages(roomId);
        return ResponseEntity.ok(ApiResponse.success(messages));
    }

    @Operation(summary = "메시지 삭제")
    @DeleteMapping("/rooms/{roomId}/messages/{messageId}")
    public ResponseEntity<ApiResponse<Void>> deleteMessage(@PathVariable Long roomId, @PathVariable String messageId) {
        chatService.deleteMessage(messageId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "채팅 참여자 조회")
    @GetMapping("/rooms/{roomId}/participants")
    public ResponseEntity<ApiResponse<List<ChatParticipantResponse>>> getParticipants(@PathVariable Long roomId) {
        List<ChatParticipant> participants = chatService.getParticipants(roomId);
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
    public ResponseEntity<ApiResponse<ChatMessageDoc>> sendMessage(@PathVariable Long roomId, @RequestBody SendMessageRequest req) {
        ChatMessageDoc savedMsg = chatService.saveMessage(roomId, req.senderId(), req.content());
        return ResponseEntity.ok(ApiResponse.success(savedMsg));
    }

}
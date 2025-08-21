package core.domain.chat.controller;


import core.domain.chat.dto.ChatMessageResponse;
import core.domain.chat.dto.ChatParticipantResponse;
import core.domain.chat.dto.ChatRoomResponse;
import core.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@Tag(name = "채팅 API", description = "1:1 채팅, 그룹 채팅, 메시지 검색/삭제 등 채팅 기능 API")
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    @Operation(summary = "채팅방 생성", description = "1:1 또는 그룹 채팅방을 생성합니다.")
    @PostMapping("/rooms")
    public ResponseEntity<ApiResponse<ChatRoomResponse>> createChatRoom(@RequestParam boolean isGroup) {
        ChatRoomResponse room = new ChatRoomResponse(
                1L,
                isGroup,
                Instant.now(),
                List.of()
        );
        return ResponseEntity.ok(ApiResponse.success(room));
    }

    @Operation(summary = "채팅방 리스트 조회", description = "사용자가 속한 모든 채팅방 목록을 조회합니다.")
    @GetMapping("/rooms")
    public ResponseEntity<ApiResponse<List<ChatRoomResponse>>> getChatRooms() {
        List<ChatRoomResponse> rooms = List.of(
                new ChatRoomResponse(1L, false, Instant.now(), List.of()),
                new ChatRoomResponse(2L, true, Instant.now(), List.of())
        );
        return ResponseEntity.ok(ApiResponse.success(rooms));
    }

    @Operation(summary = "채팅방 삭제", description = "채팅방을 삭제합니다.")
    @DeleteMapping("/rooms/{roomId}")
    public ResponseEntity<ApiResponse<Void>> deleteChatRoom(@PathVariable Long roomId) {
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "채팅방 검색", description = "채팅방 이름 또는 참여자 기준으로 검색합니다.")
    @GetMapping("/rooms/search")
    public ResponseEntity<ApiResponse<List<ChatRoomResponse>>> searchChatRooms(@RequestParam String keyword) {
        return ResponseEntity.ok(ApiResponse.success(List.of()));
    }

    @Operation(summary = "메시지 검색", description = "채팅방 내 메시지를 키워드로 검색합니다.")
    @GetMapping("/rooms/{roomId}/messages/search")
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> searchMessages(
            @PathVariable Long roomId,
            @RequestParam String keyword
    ) {
        List<ChatMessageResponse> messages = List.of(
                new ChatMessageResponse("abc123", 1L, "홍길동", "안녕하세요", Instant.now())
        );
        return ResponseEntity.ok(ApiResponse.success(messages));
    }

    @Operation(summary = "메시지 삭제", description = "채팅방 내 특정 메시지를 삭제합니다.")
    @DeleteMapping("/rooms/{roomId}/messages/{messageId}")
    public ResponseEntity<ApiResponse<Void>> deleteMessage(
            @PathVariable Long roomId,
            @PathVariable String messageId
    ) {
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "채팅 참여자 조회", description = "채팅방에 속한 모든 참여자 목록을 조회합니다.")
    @GetMapping("/rooms/{roomId}/participants")
    public ResponseEntity<ApiResponse<List<ChatParticipantResponse>>> getParticipants(@PathVariable Long roomId) {
        List<ChatParticipantResponse> participants = List.of(
                new ChatParticipantResponse(1L, 1L, "홍길동", Instant.now(), null, false, false)
        );
        return ResponseEntity.ok(ApiResponse.success(participants));
    }
}
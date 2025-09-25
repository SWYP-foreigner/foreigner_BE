package core.domain.chat.controller;
import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.service.ChatAiService;
import core.domain.chat.service.ChatService;
import core.global.config.CustomUserDetails;
import core.global.dto.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "AI 채팅", description = "AI와의 채팅방 생성, 메시지 전송, 내역 조회, 나가기")
@RestController
@RequestMapping("/api/v1/chat/ai")
@RequiredArgsConstructor
public class ChatAiController {

    private final ChatAiService chatAiService;

    @Operation(summary = "AI와 새로운 채팅방 생성", description = "AI와 1:1 채팅방을 생성합니다. 기존 방이 있으면 isNew: false, 새로 생성되면 isNew: true를 반환합니다.")
    @PostMapping("/rooms")
    public ResponseEntity<ApiResponse<ChatAiRoomResponse>> createAiRoom() {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        ChatAiRoomResponse response = chatAiService.createAiChatRoom(principal.getUserId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }


    @Operation(summary = "AI 채팅방에 메시지 보내기", description = "지정된 AI 채팅방에 메시지를 보내고 AI의 답변을 받습니다.")
    @PostMapping("/rooms/{roomId}/messages")
    public ResponseEntity<ApiResponse<AiMessageResponse>> sendMessage(
            @PathVariable Long roomId,
            @RequestBody AiMessageRequest request
    ) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        AiMessageResponse response = chatAiService.sendMessageToAi(principal.getUserId(), roomId, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
    @Operation(summary = "AI 채팅방 나가기", description = "AI와의 채팅방에서 나갑니다. (참여 상태 변경)")
    @DeleteMapping("/rooms/{roomId}")
    public ResponseEntity<ApiResponse<Void>> leaveRoom(
            @PathVariable Long roomId
    ) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        chatAiService.deleteAiChatRoom(principal.getUserId(), roomId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "AI 채팅방 메시지 불러오기 (무한 스크롤)", description = "특정 AI 채팅방의 대화 기록을 20개씩 불러옵니다. 최초 요청 시에는 lastMessageId를 보내지 마세요.")
    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<ApiResponse<MessageSliceResponse>> getMessages(
            @PathVariable Long roomId,
            @RequestParam(required = false) Long lastMessageId
    ) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        MessageSliceResponse messages = chatAiService.getChatMessages(principal.getUserId(), roomId, lastMessageId);
        return ResponseEntity.ok(ApiResponse.success(messages));
    }


}
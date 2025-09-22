package core.domain.chat.controller;
import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatRoom;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@Tag(name = "ai 채팅 ", description = "ai와 의 채팅방 생성,메세지 보내기,나가기")
@RestController
@RequestMapping("/api/v1/chat/ai")
@RequiredArgsConstructor
public class ChatAiController {
    private final ChatService chatService;

    @Operation(summary = "1:1 새로운 채팅방 생성", description = "1:1 채팅방을 생성합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = ChatRoomResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청",
                    content = @Content(schema = @Schema(implementation = Object.class))
            )
    })
    @PostMapping("/rooms/oneTone")
    public ResponseEntity<ApiResponse<ChatRoomResponse>> createRoom(
            @RequestBody CreateRoomRequest request
    ) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = principal.getUserId();
        ChatRoom room = chatService.createRoom(userId, request.otherUserId());
        ChatRoomResponse response = ChatRoomResponse.from(room);
        return ResponseEntity.ok(ApiResponse.success(response));
    }



}
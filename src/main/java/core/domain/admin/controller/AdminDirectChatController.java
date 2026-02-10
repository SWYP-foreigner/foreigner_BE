package core.domain.admin.controller;

import core.domain.chat.dto.ChatMessageResponse;
import core.domain.chat.dto.ChatRoomSummaryResponse;
import core.domain.chat.service.ChatMessageService;
import core.domain.chat.service.ChatRoomService;
import core.global.config.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Controller
@RequestMapping("/admin/direct-chat")
@RequiredArgsConstructor
public class AdminDirectChatController {

    private final ChatRoomService chatRoomService;
    private final ChatMessageService chatMessageService;

    @GetMapping
    public String chatPage(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            Model model,
            HttpServletRequest request
    ) {
        if (userDetails != null) {
            model.addAttribute("currentUserId", userDetails.getUserId());

            if (request.getCookies() != null) {
                for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                    if ("accessToken".equals(cookie.getName())) {
                        model.addAttribute("accessToken", cookie.getValue());
                        break;
                    }
                }
            }
        }
        return "admin/chat-room";
    }

    @GetMapping("/api/rooms")
    @ResponseBody
    public ResponseEntity<List<ChatRoomSummaryResponse>> getMyChatRooms(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        List<ChatRoomSummaryResponse> rooms = chatRoomService.getMyAllChatRoomSummaries(userDetails.getUserId());
        return ResponseEntity.ok(rooms);
    }

    @GetMapping("/api/rooms/{roomId}/messages")
    @ResponseBody
    public ResponseEntity<List<ChatMessageResponse>> getMessages(
            @PathVariable Long roomId,
            @RequestParam(required = false) Long lastMessageId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        List<ChatMessageResponse> messages = chatMessageService.getMessages(roomId, userDetails.getUserId(), lastMessageId);
        return ResponseEntity.ok(messages);
    }
}

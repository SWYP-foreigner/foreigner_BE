package core.global.controller;

import core.domain.chat.dto.ChatParticipantInfoDto;
import core.domain.chat.dto.ChatRoomListResponse;
import core.domain.chat.dto.ChatRoomSearchRequest;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatRoomRepository;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import core.global.service.ChatAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/chats")
@RequiredArgsConstructor
public class ChatAdminViewController {

    private final ChatAdminService chatAdminService;
    private final ChatRoomRepository chatRoomRepository;

    @GetMapping
    public String chatRoomListPage(
            @ModelAttribute ChatRoomSearchRequest request,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            Model model
    ) {
        Page<ChatRoomListResponse> chatRoomPage = chatAdminService.searchChatRooms(request, pageable);
        model.addAttribute("chatRoomPage", chatRoomPage);
        model.addAttribute("searchRequest", request);
        return "admin/chat-list";
    }

    @GetMapping("/{roomId}")
    public String chatRoomDetailPage(
            @PathVariable Long roomId,
            @PageableDefault(size = 10, sort = "joinedAt", direction = Sort.Direction.DESC) Pageable pageable, // 참여일 최신순
            Model model
    ) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND)); // CHATROOM_NOT_FOUND ErrorCode 필요

        Page<ChatParticipantInfoDto> participantPage = chatAdminService.getChatRoomParticipants(roomId, pageable);

        model.addAttribute("chatRoom", chatRoom);
        model.addAttribute("participantPage", participantPage);

        return "admin/chat-detail";
    }
}

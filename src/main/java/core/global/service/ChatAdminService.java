package core.global.service;

import core.domain.chat.dto.ChatParticipantInfoDto;
import core.domain.chat.dto.ChatRoomListResponse;
import core.domain.chat.dto.ChatRoomSearchRequest;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.global.enums.ErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatAdminService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final ChatMessageRepository chatMessageRepository;

    @Transactional(readOnly = true)
    public Page<ChatRoomListResponse> searchChatRooms(ChatRoomSearchRequest request, Pageable pageable) {
        return chatRoomRepository.searchChatRooms(request, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ChatParticipantInfoDto> getChatRoomParticipants(Long roomId, Pageable pageable) {
        return chatParticipantRepository.findByChatRoomId(roomId, pageable)
                .map(ChatParticipantInfoDto::from);
    }

    @Transactional
    public void deleteChatRoom(Long roomId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        chatMessageRepository.deleteAllByChatRoomId(roomId);
        chatRoomRepository.delete(chatRoom);
    }
}
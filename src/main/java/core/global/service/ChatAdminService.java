package core.global.service;

import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatReport;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatReportRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.global.enums.ChatReportStatus;
import core.global.enums.errorcode.ChatErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatAdminService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatReportRepository chatReportRepository;

    @Transactional(readOnly = true)
    public Page<ChatRoomListResponse> searchChatRooms(ChatRoomSearchRequest request, Pageable pageable) {
        Page<ChatRoomListResponse> page = chatRoomRepository.searchChatRooms(request, pageable);

        return page.map(room -> {
            if (!room.isGroup()) {
                List<String> names = chatParticipantRepository.findParticipantNamesByRoomId(room.chatRoomId());

                String newName;
                if (!names.isEmpty()) {
                    newName = String.join(", ", names);
                } else {
                    newName = "(참여자 없음)";
                }

                return new ChatRoomListResponse(
                        room.chatRoomId(),
                        newName,
                        room.isGroup(),
                        room.participantCount(),
                        room.createdAt()
                );
            }

            return room;
        });
    }

    @Transactional(readOnly = true)
    public Page<ChatParticipantInfoDto> getChatRoomParticipants(Long roomId, Pageable pageable) {
        return chatParticipantRepository.findByChatRoomId(roomId, pageable)
                .map(ChatParticipantInfoDto::from);
    }

    @Transactional
    public void deleteChatRoom(Long roomId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

        chatMessageRepository.deleteAllByChatRoomId(roomId);
        chatRoomRepository.delete(chatRoom);
    }

    @Transactional(readOnly = true)
    public Page<ChatMessageSearchResultDto> searchMessages(ChatMessageSearchRequest request, Pageable pageable) {
        return chatMessageRepository.searchMessages(request, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ChatReportDto> getPendingChatReports(Pageable pageable) {
        return chatReportRepository.findByStatus(ChatReportStatus.PENDING, pageable)
                .map(ChatReportDto::from);
    }

    @Transactional
    public void processChatReport(Long reportId) {
        ChatReport report = chatReportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.REPORT_NOT_FOUND));

        report.processReport();
    }

    @Transactional(readOnly = true)
    public Page<ChatMessageDetailDto> getChatRoomMessages(Long roomId, Pageable pageable) {
        return chatMessageRepository.findAllByChatRoomId(roomId, pageable)
                .map(ChatMessageDetailDto::from);
    }

    @Transactional
    public void deleteChatMessage(Long messageId) {
        ChatMessage message = chatMessageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.MESSAGE_NOT_FOUND));

        chatMessageRepository.delete(message);
    }
}
package core.domain.admin.service;

import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatReport;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatReportRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.chat.service.ChatRoomService;
import core.global.enums.chat.ChatReportStatus;
import core.global.enums.errorcode.ChatErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatAdminService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatReportRepository chatReportRepository;
    private final ChatRoomService chatRoomService;

    @Transactional(readOnly = true)
    public Page<ChatRoomListResponse> searchChatRooms(ChatRoomSearchRequest request, Pageable pageable) {
        Page<ChatRoomListResponse> page = chatRoomRepository.searchChatRooms(request, pageable);

        return page.map(room -> {
            if (!room.isGroup()) {
                List<String> names = chatParticipantRepository.findParticipantNamesByRoomId(room.chatRoomId());
                String newName = names.isEmpty() ? "(참여자 없음)" : String.join(", ", names);

                return new ChatRoomListResponse(
                        room.chatRoomId(),
                        newName,
                        room.isGroup(),
                        room.participantCount(),
                        room.createdAt(),
                        room.messageCount()
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
        // 1. 네이티브 쿼리 호출 (Page<Object[]> 반환)
        Page<Object[]> resultPage = chatMessageRepository.searchMessagesNative(
                request.keyword(),
                request.senderEmail(),
                request.senderName(),
                pageable
        );

        // 2. 수동 매핑 (데이터가 많을수록 여기서도 CPU를 꽤 씁니다)
        return resultPage.map(row -> new ChatMessageSearchResultDto(
                ((Number) row[0]).longValue(),
                ((Number) row[1]).longValue(),
                (String) row[2],
                ((Number) row[3]).longValue(),
                (String) row[4],
                (String) row[5],
                (String) row[6],
                convertToInstant(row[7]) // 강제 캐스팅 대신 유연하게 처리
        ));
    }
    private Instant convertToInstant(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Instant) {
            return (Instant) obj;
        }
        if (obj instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) obj).toInstant();
        }
        if (obj instanceof java.util.Date) {
            return ((java.util.Date) obj).toInstant();
        }
        throw new IllegalArgumentException("지원하지 않는 날짜 타입입니다: " + obj.getClass());
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

    @Transactional
    public void forceParticipantLeave(Long roomId, Long userId) {
        chatRoomService.leaveRoom(roomId, userId);
    }
}
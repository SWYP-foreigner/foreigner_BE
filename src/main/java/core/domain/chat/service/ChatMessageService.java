package core.domain.chat.service;

import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.UserRepository;
import core.domain.user.service.UserRoleDetectService;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.enums.ChatParticipantStatus;
import core.global.enums.ImageType;
import core.global.enums.errorcode.ChatErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import core.global.service.TranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatMessageService {

    private static final int MESSAGE_PAGE_SIZE = 20;

    private final ChatMessageRepository chatMessageRepository;
    private final ChatParticipantRepository participantRepo;
    private final ChatRoomRepository chatRoomRepo;
    private final UserRepository userRepository;
    private final ImageRepository imageRepository;
    private final BlockRepository blockRepository;
    private final UserRoleDetectService userRoleDetectService;
    private final TranslationService translationService;
    private final S3Presigner s3Presigner;

    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    @Lazy
    public void setMessagingTemplate(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    // --- 메시지 조회 (HTTP) ---
    @Transactional
    public List<ChatMessageResponse> getMessages(Long roomId, Long userId, Long lastMessageId) {
        ChatParticipant participant = participantRepo.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_CHAT_PARTICIPANT));
        userRoleDetectService.isProfileSetUpUser(participant.getUser());

        List<ChatMessage> rawMessages = fetchRawMessages(roomId, participant, lastMessageId);

        // 차단된 유저 메시지 필터링 및 DTO 변환
        List<Long> blockedIds = blockRepository.findByUser(participant.getUser()).stream()
                .map(b -> b.getBlocked().getId()).toList();

        List<ChatMessage> filtered = rawMessages.stream()
                .filter(m -> !blockedIds.contains(m.getSender().getId()))
                .toList();

        return convertToResponses(filtered, participant);
    }

    // --- WebSocket 관련 (전송, 읽음처리, 삭제) ---
    @Transactional
    public void processAndSendChatMessage(SendMessageRequest req) {
        // 1. DB 저장
        ChatMessage savedMsg = saveMessage(req.roomId(), req.senderId(), req.content());

        // 2. 응답 DTO 변환
        // 3. Socket 전송 (/topic/rooms/{roomId})
        // (기존 processAndSendChatMessage 로직 복사)
    }

    @Transactional
    public void processMarkAsRead(MarkAsReadRequest req, Long readerId) {
        // (기존 processMarkAsRead 로직 복사)
        // 읽음 처리 후 Socket으로 ReadCount 업데이트 전송
    }

    @Transactional
    public void deleteMessageAndBroadcast(Long messageId, Long userId) {
        // 메시지 삭제 및 삭제 이벤트 Socket 전송
    }

    @Transactional
    public void processAndSendMediaMessage(SendMediaMessageRequest req) {
        // 미디어 메시지 저장 및 전송
    }

    // --- 유틸리티 ---
    @Transactional
    public void markAllMessagesAsReadInRoom(Long roomId, Long userId) {
        // 해당 방의 모든 메시지를 읽음 처리
    }

    public PresignedUrlResponse generateChatPresignedUrl(Long roomId, String fileName) {
        // S3 URL 발급 로직
        return null;
    }

    public List<ChatMessageFirstResponse> getFirstMessages(Long roomId, Long userId) { return new ArrayList<>(); }
    public List<ChatMessageResponse> searchMessages(Long roomId, Long userId, String keyword) { return new ArrayList<>(); }
    public List<ChatMessageResponse> getMessagesAround(Long roomId, Long userId, Long messageId) { return new ArrayList<>(); }

    // --- Internal Helpers ---
    private ChatMessage saveMessage(Long roomId, Long senderId, String content) {
        // 메시지 저장 및 나간 유저 재입장 처리 로직
        return null;
    }

    private List<ChatMessage> fetchRawMessages(Long roomId, ChatParticipant p, Long lastId) {
        // 페이징 처리하여 Raw Message 조회
        return new ArrayList<>();
    }

    private List<ChatMessageResponse> convertToResponses(List<ChatMessage> messages, ChatParticipant viewer) {
        // 번역 여부 확인 후 변환
        return new ArrayList<>();
    }
}
package core.domain.chat.service;
import core.domain.admin.service.PerspectiveService;
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
import core.global.entity.image.repository.ImageRepository;
import core.global.enums.chat.ChatParticipantStatus;
import core.global.enums.common.ImageType;
import core.global.enums.errorcode.ChatErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import core.global.redis.service.RedisService;
import core.global.websocket.config.StompChannelInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatDbService {
    private final ChatRoomRepository chatRoomRepository;
    private final ChatTranslationService chatTranslationService;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final UserRepository userRepository;
    private final PerspectiveService perspectiveService;
    private final ChatMemberService chatMemberService;

    @Transactional
    public ChatTransactionResult saveAndProcessBusinessRules(SendMessageRequest req) {
        ChatMessage savedMessage = saveMessage(req.roomId(), req.senderId(), req.content());
        ChatRoom chatRoom = fetchChatRoomWithParticipants(req.roomId());
        handleBusinessRules(chatRoom, savedMessage);
        return new ChatTransactionResult(savedMessage, chatRoom);
    }
    @Transactional
    public void saveTranslations(Long messageId, Map<String, String> translations) {
        translations.forEach((lang, content) ->
                chatTranslationService.saveTranslationAsync(messageId, lang, content)
        );
    }
    @Transactional
    public ChatMessage saveMessage(Long roomId, Long senderId, String content) {
        User sender = userRepository.findById(senderId).orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        ChatRoom room = chatRoomRepository.findById(roomId).orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

        // 나간 유저 재입장 처리
        chatParticipantRepository.findByChatRoomIdAndUserId(roomId, senderId)
                .ifPresent(p -> {
                    if (p.getStatus() == ChatParticipantStatus.LEFT) p.reJoin();
                });

        if (!room.getIsGroup()) {
            chatParticipantRepository.findByChatRoomId(roomId).stream()
                    .filter(p -> !p.getUser().getId().equals(senderId) && p.getStatus() == ChatParticipantStatus.LEFT)
                    .forEach(ChatParticipant::reJoin);
        }
        ChatMessage message = new ChatMessage(room, sender, content);
        room.updateLastMessageSentAt(message.getSentAt());
        room.updateLastMessageSentAt(message.getSentAt());

        return chatMessageRepository.saveAndFlush(message);
    }
    private ChatRoom fetchChatRoomWithParticipants(Long roomId) {
        return chatRoomRepository.findChatRoomWithParticipantsAndUsers(roomId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_CHAT_PARTICIPANT));
    }
    private void handleBusinessRules(ChatRoom chatRoom, ChatMessage message) {
        if (Boolean.FALSE.equals(chatRoom.getIsGroup())) {
            reviveParticipantsIfDm(chatRoom);
        }
        runSpamCheckAsync(message);
    }
    private void reviveParticipantsIfDm(ChatRoom chatRoom) {
        if (chatRoom.getParticipants() == null || chatRoom.getParticipants().isEmpty()) {
            return;
        }

        for (ChatParticipant participant : chatRoom.getParticipants()) {
            if (participant.getStatus() == ChatParticipantStatus.LEFT) {
                log.info("1:1 채팅 메시지 전송으로 인한 유저 복구(Rejoin). RoomId: {}, UserId: {}",
                        chatRoom.getId(), participant.getUser().getId());

                participant.reJoin();
            }
        }
    }
    private void runSpamCheckAsync(ChatMessage message) {
        CompletableFuture.runAsync(() -> checkSpamAndReport(message));
    }
    /**
     * AI 스팸 감지 로직 (비동기 실행용)
     */
    private void checkSpamAndReport(ChatMessage message) {
        String content = message.getContent();
        boolean needsAiCheck = (content.contains("http") || content.contains("www.") || content.contains(".com"));

        if (!needsAiCheck) return;

        try {
            if (perspectiveService.isHarmful(content)) {
                log.warn("AI Spam Detected: messageId={}", message.getId());
                ChatReportRequest reportRequest = new ChatReportRequest(
                        message.getId(), "AI_DETECTED_SPAM", "Perspective API 감지"
                );
                chatMemberService.reportChat(null, reportRequest);
            }
        } catch (Exception e) {
            log.error("Async AI Check failed", e);
        }
    }
}

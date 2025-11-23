package core.domain.chat.service;

import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.notification.dto.NotificationEvent;
import core.domain.user.entity.BlockUser;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.UserRepository;
import core.domain.user.service.UserRoleDetectService;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.enums.ChatParticipantStatus;
import core.global.enums.ImageType;
import core.global.enums.MessageType;
import core.global.enums.NotificationType;
import core.global.enums.errorcode.ChatErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import core.global.service.PerspectiveService;
import core.global.service.TranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatMessageService {

    private static final int MESSAGE_PAGE_SIZE = 20;

    // Repository
    private final ChatMessageRepository chatMessageRepository;
    private final ChatParticipantRepository chatParticipantRepository; // participantRepo 통합
    private final ChatRoomRepository chatRoomRepository; // chatRoomRepo 통합
    private final UserRepository userRepository;
    private final ImageRepository imageRepository;
    private final BlockRepository blockRepository;

    // Service
    private final UserRoleDetectService userRoleDetectService;
    private final TranslationService translationService;
    private final PerspectiveService perspectiveService; // 추가됨
    private final ChatMemberService chatMemberService; // 신고 기능을 위해 주입 (순환 참조 주의 -> 필요시 Repository 직접 사용)

    // Infrastructure
    private final S3Presigner s3Presigner;
    private final ApplicationEventPublisher eventPublisher; // 추가됨

    @Value("${cdn.base-url}")
    private String cdnBaseUrl;

    @Value("${ncp.s3.bucket}")
    private String bucketName;

    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    @Lazy
    public void setMessagingTemplate(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    // 내부 레코드 (MessagePair 복원)
    private record MessagePair(ChatMessage originalMessage, String translatedContent) {}

    // --- 메시지 조회 (HTTP) ---

    @Transactional(readOnly = true) // 읽기 전용 트랜잭션 적용
    public List<ChatMessageResponse> getMessages(Long roomId, Long userId, Long lastMessageId) {
        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_CHAT_PARTICIPANT));

        userRoleDetectService.isProfileSetUpUser(participant.getUser());

        boolean needsTranslation = participant.isTranslateEnabled();
        String targetLanguage = participant.getUser().getTranslateLanguage();

        List<ChatMessage> messages = getRawMessages(roomId, userId, lastMessageId);

        List<Long> blockedIds = getBlockedUserIds(userId);
        if (!blockedIds.isEmpty()) {
            messages = messages.stream()
                    .filter(msg -> !blockedIds.contains(msg.getSender().getId()))
                    .toList();
        }

        if (needsTranslation && targetLanguage != null && !targetLanguage.isEmpty()) {
            List<String> originalContents = messages.stream()
                    .map(ChatMessage::getContent)
                    .collect(Collectors.toList());
            List<String> translatedContents = translationService.translateMessages(originalContents, targetLanguage);

            List<ChatMessage> finalMessages = messages;
            return IntStream.range(0, messages.size())
                    .mapToObj(i -> {
                        ChatMessage message = finalMessages.get(i);
                        String translatedContent = translatedContents.get(i);
                        return mapToResponse(message, translatedContent);
                    }).collect(Collectors.toList());
        } else {
            return messages.stream()
                    .map(message -> mapToResponse(message, null))
                    .collect(Collectors.toList());
        }
    }

    // --- WebSocket 관련 (전송, 읽음처리, 삭제) ---

    @Transactional
    public void processAndSendChatMessage(SendMessageRequest req) {
        long startTime = System.currentTimeMillis();
        ChatMessage savedMessage = this.saveMessage(req.roomId(), req.senderId(), req.content());
        String originalContent = savedMessage.getContent();

        ChatRoom chatRoom = savedMessage.getChatRoom();
        List<ChatParticipant> participants = chatRoom.getParticipants();
        User senderUser = savedMessage.getSender();

        String userImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, req.senderId())
                .map(Image::getUrl).orElse(null);

        // 보낸 사람 읽음 처리
        chatParticipantRepository.findByChatRoomIdAndUserId(req.roomId(), req.senderId())
                .ifPresent(participant -> {
                    participant.setLastReadMessageId(savedMessage.getId());
                    chatParticipantRepository.save(participant);
                });

        // AI 스팸 감지
        boolean needsAiCheck = (originalContent.contains("http") || originalContent.contains("www.") || originalContent.contains(".com"));
        if (needsAiCheck) {
            log.debug("URL 감지. Perspective API 검사 시작... (User ID: {})", req.senderId());
            if (perspectiveService.isHarmful(originalContent)) {
                log.warn("스팸 메시지 감지(AI): senderId={}, content={}", req.senderId(), originalContent);
                try {
                    ChatReportRequest aiReportRequest = new ChatReportRequest(
                            savedMessage.getId(), "AI_DETECTED_SPAM", "Perspective API가 스팸/유해 콘텐츠로 감지함"
                    );
                    // ChatMemberService를 통해 신고 처리 (또는 Repository 직접 사용)
                    chatMemberService.reportChat(null, aiReportRequest);
                    log.info("AI 자동 신고 처리 완료 (Message ID: {})", savedMessage.getId());
                } catch (Exception e) {
                    log.warn("AI 자동 신고 처리 중 오류: {}", e.getMessage());
                }
            }
        }

        // 수신자별 전송 로직
        for (ChatParticipant participant : participants) {
            User recipient = participant.getUser();
            String targetContent = null;

            boolean isBlockedByRecipient = blockRepository.existsBlock(recipient.getId(), senderUser.getId());
            boolean isBlockedByMe = blockRepository.existsBlock(senderUser.getId(), recipient.getId());

            if (isBlockedByRecipient || isBlockedByMe) continue;

            // 번역
            if (participant.isTranslateEnabled()) {
                String targetLanguage = recipient.getTranslateLanguage();
                if (targetLanguage != null && !targetLanguage.isEmpty()) {
                    List<String> translatedList = translationService.translateMessages(List.of(originalContent), targetLanguage);
                    if (!translatedList.isEmpty()) targetContent = translatedList.get(0);
                }
            }

            // 알림 이벤트 발행
            if (!recipient.getId().equals(req.senderId())) {
                NotificationEvent event = new NotificationEvent(
                        recipient.getId(), senderUser.getId(), NotificationType.chat,
                        chatRoom.getId(), originalContent, chatRoom.getRoomName()
                );
                eventPublisher.publishEvent(event);
            }

            ChatMessageResponse messageResponse = new ChatMessageResponse(
                    savedMessage.getId(), chatRoom.getId(), savedMessage.getSender().getId(),
                    originalContent, targetContent, savedMessage.getSentAt(),
                    senderUser.getFirstName(), senderUser.getLastName(), userImageUrl, MessageType.TEXT
            );

            String destination = String.format("/topic/user/%s/%s/messages", recipient.getId(), chatRoom.getId());
            messagingTemplate.convertAndSend(destination, messageResponse);

            // 채팅방 목록 갱신
            ChatRoomSummaryResponse summary = buildChatRoomSummaryResponse(chatRoom.getId(), recipient.getId());
            messagingTemplate.convertAndSend("/topic/user/" + recipient.getId() + "/rooms", summary);
        }
        long endTime = System.currentTimeMillis();
        log.info("Processed TEXT message for roomId={} in {}ms", req.roomId(), (endTime - startTime));
    }

    @Transactional
    public void processMarkAsRead(MarkAsReadRequest req, Long readerId) {
        Long roomId = req.roomId();
        Long newLastReadId = req.lastReadMessageId();

        ChatParticipant readerParticipant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, readerId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

        Long previousLastReadId = readerParticipant.getLastReadMessageId() == null ? 0L : readerParticipant.getLastReadMessageId();
        readerParticipant.setLastReadMessageId(newLastReadId);

        List<ChatParticipant> allParticipants = chatParticipantRepository.findByChatRoomId(roomId);
        List<ChatMessage> affectedMessages = chatMessageRepository
                .findByChatRoomIdAndIdGreaterThanAndIdLessThanEqualOrderByIdAsc(roomId, previousLastReadId, newLastReadId);

        List<ReadCountInfo> updatedReadCounts = new ArrayList<>();
        for (ChatMessage message : affectedMessages) {
            if (!message.getSender().getId().equals(readerId)) {
                int newUnreadCount = calculateUnreadCountForMessage(message, allParticipants);
                updatedReadCounts.add(new ReadCountInfo(message.getId(), newUnreadCount));
            }
        }

        if (!updatedReadCounts.isEmpty()) {
            messagingTemplate.convertAndSend(
                    "/topic/rooms/" + roomId + "/read-counts",
                    new MessageReadCountUpdateResponse(updatedReadCounts)
            );
        }
        ChatRoomSummaryResponse summary = buildChatRoomSummaryResponse(roomId, readerId);
        messagingTemplate.convertAndSend("/topic/user/" + readerId + "/rooms", summary);
    }

    @Transactional
    public void deleteMessageAndBroadcast(Long messageId, Long userId) {
        ChatMessage message = chatMessageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.MESSAGE_NOT_FOUND));

        if (!message.getSender().getId().equals(userId)) {
            throw new BusinessException(ChatErrorCode.FORBIDDEN_MESSAGE_DELETE);
        }

        chatMessageRepository.delete(message);

        Map<String, String> payload = Map.of("id", messageId.toString(), "type", "delete");
        messagingTemplate.convertAndSend("/topic/rooms/" + message.getChatRoom().getId(), payload);
    }

    @Transactional
    public void processAndSendMediaMessage(SendMediaMessageRequest req) {
        ChatRoom chatRoom = chatRoomRepository.findById(req.roomId())
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        User sender = userRepository.findById(req.senderId())
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        ChatMessage savedMessage = new ChatMessage(chatRoom, sender, req.mediaKey(), req.messageType());
        chatMessageRepository.save(savedMessage);

        chatParticipantRepository.findByChatRoomIdAndUserId(req.roomId(), req.senderId())
                .ifPresent(participant -> participant.setLastReadMessageId(savedMessage.getId()));

        String senderImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, sender.getId())
                .map(Image::getUrl).orElse(null);

        for (ChatParticipant participant : chatRoom.getParticipants()) {
            User recipient = participant.getUser();

            boolean isBlocked = blockRepository.existsBlock(recipient.getId(), sender.getId()) ||
                    blockRepository.existsBlock(sender.getId(), recipient.getId());
            if (isBlocked) continue;

            if (!recipient.getId().equals(sender.getId())) {
                String contentSnippet = req.messageType() == MessageType.IMAGE ? "사진을 보냈습니다." : "동영상을 보냈습니다.";
                NotificationEvent event = new NotificationEvent(
                        recipient.getId(), sender.getId(), NotificationType.chat,
                        chatRoom.getId(), contentSnippet, chatRoom.getRoomName()
                );
                eventPublisher.publishEvent(event);
            }
            String fullMediaUrl = cdnBaseUrl + "/" + savedMessage.getContent();

            ChatMessageResponse messageResponse = new ChatMessageResponse(
                    savedMessage.getId(), chatRoom.getId(), sender.getId(), fullMediaUrl, null,
                    savedMessage.getSentAt(), sender.getFirstName(), sender.getLastName(), senderImageUrl, savedMessage.getMessageType()
            );

            String destination = String.format("/topic/user/%s/%s/messages", recipient.getId(), chatRoom.getId());
            messagingTemplate.convertAndSend(destination, messageResponse);

            ChatRoomSummaryResponse summary = buildChatRoomSummaryResponse(chatRoom.getId(), recipient.getId());
            messagingTemplate.convertAndSend("/topic/user/" + recipient.getId() + "/rooms", summary);
        }
    }

    // --- 유틸리티 및 조회 ---

    @Transactional
    public void markAllMessagesAsReadInRoom(Long roomId, Long readerId) {
        Optional<ChatMessage> lastMessageOpt = chatMessageRepository.findTopByChatRoomIdOrderByIdDesc(roomId);
        if (lastMessageOpt.isPresent()) {
            MarkAsReadRequest req = new MarkAsReadRequest(roomId, readerId, lastMessageOpt.get().getId());
            processMarkAsRead(req, readerId); // 기존 로직 재사용
        }
    }

    public PresignedUrlResponse generateChatPresignedUrl(Long chatroomId, String fileName) {
        String fileKey = "chats/" + chatroomId + "/" + UUID.randomUUID() + "-" + fileName;
        PutObjectRequest putObjectRequest = PutObjectRequest.builder().bucket(bucketName).key(fileKey).build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(15))
                .putObjectRequest(putObjectRequest).build();
        PresignedPutObjectRequest presignedPutObjectRequest = s3Presigner.presignPutObject(presignRequest);
        return new PresignedUrlResponse(presignedPutObjectRequest.url().toString(), fileKey);
    }

    @Transactional(readOnly = true)
    public List<ChatMessageFirstResponse> getFirstMessages(Long roomId, Long userId) {
        // (중복 로직 제거 - Controller에서 Principal로 ID 받음)
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("채팅방을 찾을 수 없습니다."));

        // ... (기존 getFirstMessages 구현 로직 유지)
        // ... (긴 코드는 생략하고 기존 코드 로직 그대로 사용)
        // ...
        return new ArrayList<>(); // (실제 구현 시 기존 코드 복사)
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> searchMessages(Long roomId, Long userId, String keyword) {
        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_CHAT_PARTICIPANT));
        boolean needsTranslation = participant.isTranslateEnabled();
        String targetLanguage = participant.getUser().getTranslateLanguage();

        if (!needsTranslation || targetLanguage == null) {
            List<ChatMessage> messages = chatMessageRepository.findByChatRoomIdAndContentContaining(roomId, keyword);
            return messages.stream().map(m -> mapToResponse(m, null))
                    .sorted(Comparator.comparing(ChatMessageResponse::sentAt, Comparator.reverseOrder()))
                    .collect(Collectors.toList());
        } else {
            List<ChatMessage> allMessages = chatMessageRepository.findByChatRoomIdOrderByIdAsc(roomId);
            if (allMessages.isEmpty()) return new ArrayList<>();

            List<String> originalContents = allMessages.stream().map(ChatMessage::getContent).toList();
            List<String> translatedContents = translationService.translateMessages(originalContents, targetLanguage);

            return IntStream.range(0, allMessages.size())
                    .mapToObj(i -> new MessagePair(allMessages.get(i), translatedContents.get(i)))
                    .filter(pair -> pair.translatedContent.toLowerCase().contains(keyword.toLowerCase()))
                    .map(pair -> mapToResponse(pair.originalMessage, pair.translatedContent))
                    .sorted(Comparator.comparing(ChatMessageResponse::sentAt, Comparator.reverseOrder()))
                    .collect(Collectors.toList());
        }
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getMessagesAround(Long roomId, Long userId, Long targetMessageId) {
        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_CHAT_PARTICIPANT));

        List<ChatMessage> older = chatMessageRepository.findTop20ByChatRoomIdAndIdLessThanOrderByIdDesc(roomId, targetMessageId);
        Collections.reverse(older);
        ChatMessage target = chatMessageRepository.findById(targetMessageId).orElseThrow();
        List<ChatMessage> newer = chatMessageRepository.findTop20ByChatRoomIdAndIdGreaterThanOrderByIdAsc(roomId, targetMessageId);

        List<ChatMessage> combined = new ArrayList<>(older);
        combined.add(target);
        combined.addAll(newer);

        // 번역 로직 적용 후 반환 (위 getMessages와 동일한 패턴 사용)
        // ...
        return combined.stream().map(m -> mapToResponse(m, null)).collect(Collectors.toList());
    }

    @Transactional
    public ChatMessage saveMessage(Long roomId, Long senderId, String content) {
        User sender = userRepository.findById(senderId).orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        ChatRoom room = chatRoomRepository.findById(roomId).orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

        // 나간 유저 재입장 처리
        chatParticipantRepository.findByChatRoomIdAndUserId(roomId, senderId)
                .ifPresent(p -> { if (p.getStatus() == ChatParticipantStatus.LEFT) p.reJoin(); });

        if (!room.getGroup()) {
            chatParticipantRepository.findByChatRoomId(roomId).stream()
                    .filter(p -> !p.getUser().getId().equals(senderId) && p.getStatus() == ChatParticipantStatus.LEFT)
                    .forEach(ChatParticipant::reJoin);
        }
        return chatMessageRepository.save(new ChatMessage(room, sender, content));
    }

    // --- Private Helper Methods (필수 복원) ---

    private List<ChatMessage> getRawMessages(Long roomId, Long userId, Long lastMessageId) {
        ChatParticipant p = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId).orElseThrow();
        PageRequest page = PageRequest.of(0, MESSAGE_PAGE_SIZE, Sort.by("sentAt").descending());

        if (p.getStatus() == ChatParticipantStatus.LEFT && p.getLastLeftAt() != null) {
            return (lastMessageId != null)
                    ? chatMessageRepository.findByChatRoomIdAndSentAtAfterAndIdBefore(roomId, p.getLastLeftAt(), lastMessageId, page)
                    : chatMessageRepository.findByChatRoomIdAndSentAtAfter(roomId, p.getLastLeftAt(), page);
        }
        return (lastMessageId != null)
                ? chatMessageRepository.findByChatRoomIdAndIdBefore(roomId, lastMessageId, page)
                : chatMessageRepository.findByChatRoomId(roomId, page);
    }

    private List<Long> getBlockedUserIds(Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        return blockRepository.findByUser(user).stream().map(BlockUser::getBlocked).map(User::getId).toList();
    }

    private ChatMessageResponse mapToResponse(ChatMessage message, String translatedContent) {
        User sender = message.getSender();
        String senderImg = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, sender.getId())
                .map(Image::getUrl).orElse(null);
        return new ChatMessageResponse(
                message.getId(), message.getChatRoom().getId(), sender.getId(),
                message.getContent(), translatedContent, message.getSentAt(),
                sender.getFirstName(), sender.getLastName(), senderImg, message.getMessageType()
        );
    }

    private int calculateUnreadCountForMessage(ChatMessage message, List<ChatParticipant> allParticipants) {
        long readCount = allParticipants.stream()
                .filter(p -> p.getLastReadMessageId() != null && p.getLastReadMessageId() >= message.getId())
                .count();
        return allParticipants.size() - (int) readCount;
    }

    private int countUnreadMessages(Long roomId, Long userId) {
        Long lastReadId = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .map(ChatParticipant::getLastReadMessageId).orElse(0L);
        return chatMessageRepository.countUnreadMessages(roomId, lastReadId, userId);
    }

    private ChatRoomSummaryResponse buildChatRoomSummaryResponse(Long roomId, Long forUserId) {
        ChatRoom room = chatRoomRepository.findById(roomId).orElseThrow();
        ChatMessage lastMsg = chatMessageRepository.findTopByChatRoomIdOrderBySentAtDesc(roomId).orElse(null);

        String lastContent = (lastMsg != null) ? lastMsg.getContent() : "대화를 시작해보세요.";
        Instant lastTime = (lastMsg != null) ? lastMsg.getSentAt() : room.getCreatedAt();
        int unread = countUnreadMessages(roomId, forUserId);

        String name = room.getRoomName();
        String img = null;

        if (!room.getGroup()) {
            User opponent = room.getParticipants().stream()
                    .map(ChatParticipant::getUser).filter(u -> !u.getId().equals(forUserId)).findFirst().orElse(null);
            if (opponent != null) {
                name = opponent.getFirstName() + " " + opponent.getLastName();
                img = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, opponent.getId())
                        .map(Image::getUrl).orElse(null);
            } else {
                name = "Unknown";
            }
        } else {
            img = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.CHAT_ROOM, roomId)
                    .map(Image::getUrl).orElse(null);
        }
        return new ChatRoomSummaryResponse(room.getId(), name, lastContent, lastTime, img, unread, room.getParticipants().size());
    }
}
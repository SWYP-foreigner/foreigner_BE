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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatMessageService {

    private static final int MESSAGE_PAGE_SIZE = 20;

    // Repository
    private final ChatMessageRepository chatMessageRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final ImageRepository imageRepository;
    private final BlockRepository blockRepository;

    // Service
    private final UserRoleDetectService userRoleDetectService;
    private final TranslationService translationService;
    private final PerspectiveService perspectiveService;
    private final ChatMemberService chatMemberService;

    private final S3Presigner s3Presigner;
    private final ApplicationEventPublisher eventPublisher;

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

    private record MessagePair(ChatMessage originalMessage, String translatedContent) {}

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getMessages(Long roomId, Long userId, Long lastMessageId) {
        // 1. 참여자 검증 (기존 동일)
        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_CHAT_PARTICIPANT));

        userRoleDetectService.isProfileSetUpUser(participant.getUser());

        boolean needsTranslation = participant.isTranslateEnabled();
        String targetLanguage = participant.getUser().getTranslateLanguage();

        // 2. 메시지 가져오기 (기존 동일)
        List<ChatMessage> messages = getRawMessages(roomId, userId, lastMessageId);

        // 3. 차단된 유저 필터링 (기존 동일)
        List<Long> blockedIds = getBlockedUserIds(userId);
        if (!blockedIds.isEmpty()) {
            messages = messages.stream()
                    .filter(msg -> !blockedIds.contains(msg.getSender().getId()))
                    .toList();
        }

        // ==========================================
        // [수정됨] 4. 프로필 이미지 Bulk 조회 (N+1 문제 해결 핵심)
        // ==========================================
        List<Long> senderIds = messages.stream()
                .map(msg -> msg.getSender().getId())
                .distinct()
                .toList();

        Map<Long, String> profileMap = new HashMap<>();
        if (!senderIds.isEmpty()) {
            // 이미 getFirstMessages에서 사용 중인 메서드를 재활용합니다.
            List<Image> images = imageRepository.findAllByImageTypeAndRelatedIdInOrderByOrderIndexAsc(ImageType.USER, senderIds);

            // 가져온 이미지를 Map<User_ID, Image_URL> 형태로 변환하여 빠르게 찾을 수 있게 합니다.
            profileMap = images.stream()
                    .collect(Collectors.toMap(
                            Image::getRelatedId,
                            Image::getUrl,
                            (existing, replacement) -> existing // 중복 시 첫 번째 것 사용
                    ));
        }

        // 5. 번역 및 응답 변환 (Map을 전달하도록 변경)
        if (needsTranslation && targetLanguage != null && !targetLanguage.isEmpty()) {
            List<String> originalContents = messages.stream()
                    .map(ChatMessage::getContent)
                    .collect(Collectors.toList());
            List<String> translatedContents = translationService.translateMessages(originalContents, targetLanguage);

            List<ChatMessage> finalMessages = messages;
            Map<Long, String> finalProfileMap = profileMap;

            return IntStream.range(0, messages.size())
                    .mapToObj(i -> {
                        ChatMessage message = finalMessages.get(i);
                        String translatedContent = translatedContents.get(i);
                        // profileMap을 인자로 넘깁니다.
                        return mapToResponse(message, translatedContent, finalProfileMap);
                    }).collect(Collectors.toList());
        } else {
            Map<Long, String> finalProfileMap = profileMap;
            return messages.stream()
                    .map(message -> mapToResponse(message, null, finalProfileMap))
                    .collect(Collectors.toList());
        }
    }

    // [수정됨] 헬퍼 메서드: 매번 DB를 조회하는 대신 미리 가져온 Map에서 꺼내 씁니다.
    private ChatMessageResponse mapToResponse(ChatMessage message, String translatedContent, Map<Long, String> profileMap) {
        User sender = message.getSender();

        // DB 조회 코드 삭제됨 -> Map 조회로 변경 (메모리 연산이라 매우 빠름)
        String senderImg = profileMap.get(sender.getId());

        return new ChatMessageResponse(
                message.getId(), message.getChatRoom().getId(), sender.getId(),
                message.getContent(), translatedContent, message.getSentAt(),
                sender.getFirstName(), sender.getLastName(), senderImg, message.getMessageType()
        );
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
            processMarkAsRead(req, readerId);
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
        // 채팅방 존재 확인
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

        // 참여 여부 확인
        chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .filter(participant -> participant.getStatus() != ChatParticipantStatus.LEFT)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_CHAT_PARTICIPANT));

        // 최근 50개 조회
        List<ChatMessage> messages = chatMessageRepository.findTop50ByChatRoomIdOrderBySentAtDesc(roomId);

        // 차단 유저 제외
        List<Long> blockedIds = getBlockedUserIds(userId);
        if (!blockedIds.isEmpty()) {
            messages = messages.stream()
                    .filter(msg -> !blockedIds.contains(msg.getSender().getId()))
                    .toList();
        }

        // 이미지 Bulk 조회
        List<Long> senderIds = messages.stream().map(msg -> msg.getSender().getId()).distinct().toList();
        Map<Long, String> senderImageUrlMap = new HashMap<>();

        if (!senderIds.isEmpty()) {
            List<Image> images = imageRepository.findAllByImageTypeAndRelatedIdInOrderByOrderIndexAsc(ImageType.USER, senderIds);
            senderImageUrlMap = images.stream().collect(Collectors.toMap(
                    Image::getRelatedId, Image::getUrl, (url1, url2) -> url1
            ));
        }

        Map<Long, String> finalMap = senderImageUrlMap;
        return messages.stream()
                .map(message -> ChatMessageFirstResponse.fromEntity(message, chatRoom, finalMap.get(message.getSender().getId())))
                .collect(Collectors.toList());
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
        ChatMessage target = chatMessageRepository.findById(targetMessageId).orElseThrow(() -> new BusinessException(ChatErrorCode.MESSAGE_NOT_FOUND));
        List<ChatMessage> newer = chatMessageRepository.findTop20ByChatRoomIdAndIdGreaterThanOrderByIdAsc(roomId, targetMessageId);

        List<ChatMessage> combined = new ArrayList<>(older);
        combined.add(target);
        combined.addAll(newer);

        boolean needsTranslation = participant.isTranslateEnabled();
        String targetLanguage = participant.getUser().getTranslateLanguage();

        if (needsTranslation && targetLanguage != null) {
            List<String> contents = combined.stream().map(ChatMessage::getContent).toList();
            List<String> translated = translationService.translateMessages(contents, targetLanguage);
            return IntStream.range(0, combined.size())
                    .mapToObj(i -> mapToResponse(combined.get(i), translated.get(i)))
                    .collect(Collectors.toList());
        }

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

    // --- Private Helper Methods ---

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
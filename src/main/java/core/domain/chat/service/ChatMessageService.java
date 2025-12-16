package core.domain.chat.service;

import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.user.entity.BlockUser;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.UserRepository;
import core.domain.user.service.UserRoleDetectService;
import core.global.entity.image.dto.ImageModerationEvent;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.enums.ChatParticipantStatus;
import core.global.enums.ImageModerationStatus;
import core.global.enums.ImageType;
import core.global.enums.MessageType;
import core.global.enums.errorcode.ChatErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import core.global.metrics.ChatMetrics;
import core.global.service.PerspectiveService;
import core.global.service.TranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StopWatch;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
    private final PerspectiveService perspectiveService;
    private final ChatMemberService chatMemberService;
    private final ChatSummaryService chatSummaryService;

    private final S3Presigner s3Presigner;
    private final ApplicationEventPublisher eventPublisher;
    private final ChatMetrics chatMetrics;
    private final ChatTranslationService chatTranslationService;
    @Value("${cdn.base-url}")
    private String cdnBaseUrl;

    @Value("${ncp.s3.bucket}")
    private String bucketName;


    /**
     * 최적화된 메시지 전송 로직
     * 1. Blocking IO(AI, DB) 최소화
     * 2. N+1 문제 해결 (Block, Image)
     * 3. 반복적인 객체 생성 제거 (Summary)
     */
    /**
     * 최적화된 일반(TEXT) 메시지 전송 로직
     */

    @Transactional
    public void processAndSendChatMessage(SendMessageRequest req) {
        try {
            // 1. 메시지 저장 및 필수 데이터 조회
            ChatMessage savedMessage = this.saveMessage(req.roomId(), req.senderId(), req.content());
            ChatRoom chatRoom = fetchChatRoomWithParticipants(req.roomId());
            User sender = savedMessage.getSender();

            // 2. 비동기 스팸 체크 (Fire-and-Forget)
            runSpamCheckAsync(savedMessage);

            // 3. 부가 정보 조회 (이미지, 차단 목록)
            String userImageUrl = getUserProfileImage(sender.getId());
            List<Long> blockedUserIds = getBlockedUserIds(sender.getId());

            // 4. [핵심] 수신자 그룹핑 (언어별 분류)
            Map<String, List<Long>> recipientsByLang = groupRecipientsByLanguage(
                    chatRoom, sender, blockedUserIds, savedMessage.getId()
            );
            // 5. [핵심] 병렬 번역 실행 (Parallel Translation)
            Map<String, String> translatedContentsMap = new HashMap<>();
            if (savedMessage.getMessageType() == MessageType.TEXT) {
                // ★ 핵심: 텍스트일 때만 번역기 가동
                translatedContentsMap = executeParallelTranslations(
                        savedMessage.getId(), savedMessage.getContent(), recipientsByLang.keySet()
                );
            }
            // 6. [핵심] 그룹별 비동기 발송 (Async Dispatch)
            executeParallelDispatch(
                    recipientsByLang, translatedContentsMap, savedMessage, userImageUrl
            );

        } catch (Exception e) {
            log.error("Error in processAndSendChatMessage", e);
            throw e;
        }
    }


    private ChatRoom fetchChatRoomWithParticipants(Long roomId) {
        return chatRoomRepository.findChatRoomWithParticipantsAndUsers(roomId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_CHAT_PARTICIPANT));
    }

    private void runSpamCheckAsync(ChatMessage message) {
        CompletableFuture.runAsync(() -> checkSpamAndReport(message));
    }

    private String getUserProfileImage(Long userId) {
        return imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, userId)
                .map(Image::getUrl)
                .orElse(null);
    }


    /**
     * 수신자를 언어별로 그룹핑합니다. (SELF, NONE, ko, en ...)
     */
    private Map<String, List<Long>> groupRecipientsByLanguage(ChatRoom chatRoom, User sender, List<Long> blockedUserIds, Long messageId) {
        Map<String, List<Long>> recipientsByLang = new HashMap<>();

        for (ChatParticipant p : chatRoom.getParticipants()) {
            User recipient = p.getUser();

            if (blockedUserIds.contains(recipient.getId())) continue;

            if (recipient.getId().equals(sender.getId())) {
                p.setLastReadMessageId(messageId); // (자동 업데이트)
            }


            String lang = (p.isTranslateEnabled() && recipient.getTranslateLanguage() != null)
                    ? recipient.getTranslateLanguage()
                    : "NONE";

            recipientsByLang.computeIfAbsent(lang, k -> new ArrayList<>()).add(recipient.getId());
        }
        return recipientsByLang;
    }

    /**
     * 필요한 언어들에 대해 병렬로 번역을 수행합니다.
     */
    private Map<String, String> executeParallelTranslations(Long messageId, String originalContent, Set<String> targetLanguages) {
        Map<String, String> resultMap = new ConcurrentHashMap<>();

        List<String> languagesToTranslate = targetLanguages.stream()
                .filter(lang -> !"SELF".equals(lang) && !"NONE".equals(lang))
                .toList();

        List<CompletableFuture<Void>> futures = languagesToTranslate.stream()
                .map(lang -> CompletableFuture.runAsync(() -> {
                    try {
                        String translatedText = chatTranslationService.translateAndCache(messageId, originalContent, lang).join();

                        if (translatedText != null) {
                            resultMap.put(lang, translatedText);
                        }
                    } catch (Exception e) {
                        log.error("Translation failed for lang: {}", lang, e);
                    }
                }))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return resultMap;
    }

    /**
     * 그룹별로 메시지를 생성하고 비동기로 전송합니다.
     * (DTO 수정 없이 Record 생성자 사용)
     */
    private void executeParallelDispatch(
            Map<String, List<Long>> recipientsByLang,
            Map<String, String> translatedContentsMap,
            ChatMessage savedMessage,
            String userImageUrl
    ) {
        // 1. 트랜잭션 안에서 데이터 미리 확보 (Lazy Loading 방지)
        final Long messageId = savedMessage.getId();
        final Long roomId = savedMessage.getChatRoom().getId();
        final Long senderId = savedMessage.getSender().getId();
        final String content = savedMessage.getContent();
        final Instant sentAt = savedMessage.getSentAt();
        final String senderFirstName = savedMessage.getSender().getFirstName();
        final String senderLastName = savedMessage.getSender().getLastName();
        final MessageType msgType = savedMessage.getMessageType();

        // 2. ★ 핵심 수정: 트랜잭션 커밋 후(After Commit)에 실행되도록 예약
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 이 블록은 DB 커밋이 끝난 뒤에 실행됩니다.
                // 따라서 비동기 스레드가 DB를 조회할 때 "방금 저장한 메시지"가 무조건 보입니다.

                List<CompletableFuture<Void>> futures = new ArrayList<>();

                for (Map.Entry<String, List<Long>> entry : recipientsByLang.entrySet()) {
                    String targetLang = entry.getKey();
                    List<Long> recipientIds = entry.getValue();
                    if (recipientIds.isEmpty()) continue;

                    String translatedText = translatedContentsMap.get(targetLang);

                    // DTO 생성
                    ChatMessageResponse messageResponse = new ChatMessageResponse(
                            messageId, roomId, senderId, content, translatedText,
                            sentAt, senderFirstName, senderLastName, userImageUrl, msgType
                    );

                    // 전송 로직 (기존과 동일)
                    futures.add(CompletableFuture.runAsync(() ->
                            chatSummaryService.sendSummaryToRecipientsInNewTx(messageResponse, recipientIds)
                    ));
                }
                // Fire-and-Forget
            }
        });
    }

    @Transactional(readOnly = true)
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

        List<Long> senderIds = messages.stream()
                .map(msg -> msg.getSender().getId())
                .distinct()
                .toList();

        Map<Long, String> profileMap = new HashMap<>();
        if (!senderIds.isEmpty()) {
            List<Image> images = imageRepository.findAllByImageTypeAndRelatedIdInOrderByOrderIndexAsc(ImageType.USER, senderIds);

            profileMap = images.stream()
                    .collect(Collectors.toMap(
                            Image::getRelatedId,
                            Image::getUrl,
                            (existing, replacement) -> existing
                    ));
        }
        final Map<Long, String> finalProfileMap = profileMap;
        if (needsTranslation && targetLanguage != null && !targetLanguage.isEmpty()) {

            List<ChatMessage> textMessages = messages.stream()
                    .filter(msg -> msg.getMessageType() == MessageType.TEXT)
                    .toList();

            Map<Long, String> translatedMap = chatTranslationService.getTranslatedMessages(textMessages, targetLanguage);

            return messages.stream()
                    .map(message -> {
                        String translatedContent = (message.getMessageType() == MessageType.TEXT)
                                ? translatedMap.get(message.getId())
                                : null;

                        return mapToResponse(message, translatedContent, finalProfileMap);
                    })
                    .collect(Collectors.toList());
        } else {
            return messages.stream()
                    .map(message -> mapToResponse(message, null, finalProfileMap))
                    .collect(Collectors.toList());
        }
    }

    private ChatMessageResponse mapToResponse(ChatMessage message, String translatedContent, Map<Long, String> profileMap) {
        User sender = message.getSender();
        String senderImg = profileMap.get(sender.getId());

        String finalContent = message.getContent();
        MessageType finalType = message.getMessageType();

        if ("BLOCKED_MEDIA".equals(finalContent)) {
            finalContent = "관리자에 의해 삭제된 이미지입니다.";
            finalType = MessageType.TEXT;
            translatedContent = null;
        }
        else if (finalType == MessageType.IMAGE) {
            if (!finalContent.startsWith("http")) {
                finalContent = cdnBaseUrl + "/" + finalContent;
            }
        }

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

        // [성능 개선 포인트] 필요한 데이터만 조회
        List<ChatParticipant> allParticipants = chatParticipantRepository.findByChatRoomId(roomId);
        List<ChatMessage> affectedMessages = chatMessageRepository
                .findByChatRoomIdAndIdGreaterThanAndIdLessThanEqualOrderByIdAsc(roomId, previousLastReadId, newLastReadId);

        List<ReadCountInfo> updatedReadCounts = new ArrayList<>();
        for (ChatMessage message : affectedMessages) {
            // 내가 쓴 메시지가 아니면 unread count 계산
            if (!message.getSender().getId().equals(readerId)) {
                int newUnreadCount = calculateUnreadCountForMessage(message, allParticipants);
                updatedReadCounts.add(new ReadCountInfo(message.getId(), newUnreadCount));
            }
        }

        // [리팩토링] 웹소켓 전송 로직 제거 -> 이벤트 데이터 생성
        ChatRoomSummaryResponse summary = buildChatRoomSummaryResponse(roomId, readerId);

        // [핵심] 이벤트 발행
        eventPublisher.publishEvent(new MessageReadEvent(roomId, updatedReadCounts, readerId, summary));
    }

    @Transactional
    public void deleteMessageAndBroadcast(Long messageId, Long userId) {
        ChatMessage message = chatMessageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.MESSAGE_NOT_FOUND));

        if (!message.getSender().getId().equals(userId)) {
            throw new BusinessException(ChatErrorCode.FORBIDDEN_MESSAGE_DELETE);
        }

        Long roomId = message.getChatRoom().getId(); // ID 미리 추출
        chatMessageRepository.delete(message);

        // [리팩토링] 웹소켓 전송 로직 제거 -> 이벤트 발행
        eventPublisher.publishEvent(new MessageDeletedEvent(roomId, messageId));
    }


    @Transactional
    public void processAndSendMediaMessage(SendMediaMessageRequest req) {
        long startTime = System.currentTimeMillis();

        try {
            long absoluteStartTime = System.currentTimeMillis();
            ChatRoom chatRoom = chatRoomRepository.findById(req.roomId())
                    .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
            User sender = userRepository.findById(req.senderId())
                    .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

            // 1. 메시지 저장 (동일)
            ChatMessage savedMessage = new ChatMessage(chatRoom, sender, req.mediaKey(), req.messageType());
            chatMessageRepository.save(savedMessage);

            if (req.messageType() == MessageType.IMAGE) {
                String fullMediaUrl = cdnBaseUrl + "/" + req.mediaKey();

                Image chatImage = Image.of(
                        ImageType.CHAT_MEDIA,
                        savedMessage.getId(),
                        fullMediaUrl,
                        0,
                        ImageModerationStatus.CLEAN,
                        null
                );
                imageRepository.save(chatImage);

                eventPublisher.publishEvent(new ImageModerationEvent(chatImage.getId(), req.mediaKey()));
            }

            chatParticipantRepository.findByChatRoomIdAndUserId(req.roomId(), req.senderId())
                    .ifPresent(participant -> participant.setLastReadMessageId(savedMessage.getId()));

            // 2. 수신자 목록 계산 (리팩토링)
            // 기존의 반복문 안에서 바로 보내던 로직을 -> 받을 사람 ID만 수집하는 것으로 변경
            List<Long> recipientIds = new ArrayList<>();

            for (ChatParticipant participant : chatRoom.getParticipants()) {
                User recipient = participant.getUser();

                // 차단 체크
                boolean isBlocked = blockRepository.existsBlock(recipient.getId(), sender.getId()) ||
                        blockRepository.existsBlock(sender.getId(), recipient.getId());
                if (isBlocked) continue;
                recipientIds.add(recipient.getId());
            }

            String fullMediaUrl = cdnBaseUrl + "/" + savedMessage.getContent();
            String senderImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, sender.getId())
                    .map(Image::getUrl).orElse(null);

            ChatMessageResponse messageResponse = new ChatMessageResponse(
                    savedMessage.getId(), chatRoom.getId(), sender.getId(), fullMediaUrl, null,
                    savedMessage.getSentAt(), sender.getFirstName(), sender.getLastName(), senderImageUrl, savedMessage.getMessageType()
            );

            // 채팅방 목록 갱신용 DTO (보내는 사람 기준으로 만들거나, 수신자별로 다르다면 null로 보내고 리스너에서 처리할 수도 있음. 여기선 단순화)
            ChatRoomSummaryResponse summary = buildChatRoomSummaryResponse(chatRoom.getId(), sender.getId());

            // 4. [핵심 변경] 직접 전송하지 않고 이벤트만 던집니다.
            eventPublisher.publishEvent(new MessageSentEvent(messageResponse, recipientIds, summary));

            //chatMetrics.onMessageSent("media", true);
            // chatMetrics.recordDelivery(startTime);
        } catch (Exception e) {
            // chatMetrics.onMessageSent("media", false);
            // chatMetrics.recordDelivery(startTime);

            throw e;
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
        return messages.stream().map(message -> {
            String finalContent = message.getContent();
            MessageType finalType = message.getMessageType();

            if ("BLOCKED_MEDIA".equals(finalContent)) {
                finalContent = "관리자에 의해 삭제된 이미지입니다.";
                finalType = MessageType.TEXT;
            } else if (finalType == MessageType.IMAGE && !finalContent.startsWith("http")) {
                finalContent = cdnBaseUrl + "/" + finalContent;
            }
            return ChatMessageFirstResponse.fromEntityWithContent(message, chatRoom, finalMap.get(message.getSender().getId()), finalContent, finalType);
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> searchMessages(Long roomId, Long userId, String keyword) {
        // 1. 참여자 검증
        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_CHAT_PARTICIPANT));

        boolean needsTranslation = participant.isTranslateEnabled();
        String targetLanguage = participant.getUser().getTranslateLanguage();

        // 2. [최적화] 모든 메시지를 가져오는 대신, DB에서 검색 조건에 맞는 메시지만 가져옵니다.
        //    (findByChatRoomIdAndContentContaining 메서드 활용)
        List<ChatMessage> messages = chatMessageRepository.findByChatRoomIdAndContentContaining(roomId, keyword);

        // 검색 결과가 없으면 빈 리스트 반환 (불필요한 로직 방지)
        if (messages.isEmpty()) {
            return new ArrayList<>();
        }

        // 3. [최적화] 검색된 결과의 프로필 이미지 Bulk 조회 (N+1 해결)
        List<Long> senderIds = messages.stream()
                .map(msg -> msg.getSender().getId())
                .distinct()
                .toList();

        Map<Long, String> profileMap = imageRepository.findAllByImageTypeAndRelatedIdInOrderByOrderIndexAsc(ImageType.USER, senderIds)
                .stream()
                .collect(Collectors.toMap(
                        Image::getRelatedId,
                        Image::getUrl,
                        (existing, replacement) -> existing
                ));

        // 4. 번역 및 응답 변환 (검색된 소수의 메시지만 번역)
        if (needsTranslation && targetLanguage != null && !targetLanguage.isEmpty()) {

            // [변경] 외부 API 직접 호출(translationService) -> 캐싱 서비스(chatTranslationService) 사용
            // 검색 결과가 5개라면, 5개 중 캐시 없는 것만 골라서 API 호출 후 저장까지 수행함
            Map<Long, String> translatedMap = chatTranslationService.getTranslatedMessages(messages, targetLanguage);

            return messages.stream()
                    .map(message -> {
                        // ID로 번역된 내용 찾기 (Map 조회)
                        String translatedContent = translatedMap.get(message.getId());
                        return mapToResponse(message, translatedContent, profileMap);
                    })
                    .sorted(Comparator.comparing(ChatMessageResponse::sentAt, Comparator.reverseOrder()))
                    .collect(Collectors.toList());

        } else {
            // 번역 미사용 시
            return messages.stream()
                    .map(m -> mapToResponse(m, null, profileMap))
                    .sorted(Comparator.comparing(ChatMessageResponse::sentAt, Comparator.reverseOrder()))
                    .collect(Collectors.toList());
        }
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getMessagesAround(Long roomId, Long userId, Long targetMessageId) {
        // 1. 참여자 조회
        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_CHAT_PARTICIPANT));

        // 2. 메시지 조회 (이전 20개, 타겟, 이후 20개)
        List<ChatMessage> older = chatMessageRepository.findTop20ByChatRoomIdAndIdLessThanOrderByIdDesc(roomId, targetMessageId);
        Collections.reverse(older);

        ChatMessage target = chatMessageRepository.findById(targetMessageId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.MESSAGE_NOT_FOUND));

        List<ChatMessage> newer = chatMessageRepository.findTop20ByChatRoomIdAndIdGreaterThanOrderByIdAsc(roomId, targetMessageId);

        List<ChatMessage> combined = new ArrayList<>(older);
        combined.add(target);
        combined.addAll(newer);


        // 3. [수정됨] 번역 처리 (캐싱 서비스 적용)
        // 구형 로직(getTranslatedMessages 호출) 삭제됨
        Map<Long, String> translatedMap = Collections.emptyMap(); // 기본값 빈 맵

        if (participant.isTranslateEnabled() && participant.getUser().getTranslateLanguage() != null) {
            // chatTranslationService가 Redis -> DB -> API 순서로 체크하고 저장까지 수행
            translatedMap = chatTranslationService.getTranslatedMessages(combined, participant.getUser().getTranslateLanguage());
        }

        // 4. 응답 변환
        Map<Long, String> finalTranslatedMap = translatedMap;
        return combined.stream()
                .map(msg -> {
                    String translated = finalTranslatedMap.get(msg.getId());
                    // mapToResponse 메서드 시그니처에 맞춰 호출 (프로필 맵이 필요하다면 여기서 추가 로직 필요)
                    return mapToResponse(msg, translated);
                })
                .collect(Collectors.toList());
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
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ChatRoomSummaryResponse buildChatRoomSummaryResponse(Long roomId, Long forUserId) {
        ChatRoom room = chatRoomRepository.findById(roomId).orElseThrow();
        ChatMessage lastMsg = chatMessageRepository.findTopByChatRoomIdOrderBySentAtDesc(roomId).orElse(null);

        String lastContent = (lastMsg != null) ? lastMsg.getContent() : "대화를 시작해보세요.";
        Instant lastTime = (lastMsg != null) ? lastMsg.getSentAt() : room.getCreatedAt();
        int unread = countUnreadMessages(roomId, forUserId);

        String name = room.getRoomName();
        String img = null;

        if (!room.getIsGroup()) {
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
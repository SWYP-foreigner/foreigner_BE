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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StopWatch;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.time.Instant;
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
    private final TranslationService translationService;
    private final PerspectiveService perspectiveService;
    private final ChatMemberService chatMemberService;
    private final ChatSummaryService chatSummaryService;

    private final S3Presigner s3Presigner;
    private final ApplicationEventPublisher eventPublisher;
    private final ChatMetrics chatMetrics;

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
            Map<String, String> translatedContentsMap = executeParallelTranslations(
                    savedMessage.getContent(), recipientsByLang.keySet()
            );

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

            // 차단된 유저 제외
            if (blockedUserIds.contains(recipient.getId())) continue;

            // 보낸 사람 처리 (SELF)
            if (recipient.getId().equals(sender.getId())) {
                p.setLastReadMessageId(messageId); // Dirty Checking으로 자동 업데이트됨
                recipientsByLang.computeIfAbsent("SELF", k -> new ArrayList<>()).add(recipient.getId());
                continue;
            }

            // 언어 설정 확인
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
    private Map<String, String> executeParallelTranslations(String originalContent, Set<String> targetLanguages) {
        Map<String, String> resultMap = new ConcurrentHashMap<>(); // 병렬 처리를 위해 ConcurrentHashMap 사용 권장

        // 번역이 필요한 언어만 필터링
        List<String> languagesToTranslate = targetLanguages.stream()
                .filter(lang -> !"SELF".equals(lang) && !"NONE".equals(lang))
                .toList();

        // CompletableFuture 리스트 생성 및 실행
        List<CompletableFuture<Void>> futures = languagesToTranslate.stream()
                .map(lang -> CompletableFuture.runAsync(() -> {
                    try {
                        List<String> results = translationService.translateMessages(List.of(originalContent), lang);
                        if (!results.isEmpty()) {
                            resultMap.put(lang, results.get(0));
                        }
                    } catch (Exception e) {
                        log.error("Translation failed for lang: {}", lang, e);
                    }
                }))
                .toList();

        // 모든 번역이 끝날 때까지 대기 (Join)
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return resultMap;
    }

    /**
     * 그룹별로 메시지를 생성하고 비동기로 전송합니다.
     */
    private void executeParallelDispatch(
            Map<String, List<Long>> recipientsByLang,
            Map<String, String> translatedContentsMap,
            ChatMessage savedMessage,
            String userImageUrl
    ) {
        User sender = savedMessage.getSender();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Map.Entry<String, List<Long>> entry : recipientsByLang.entrySet()) {
            String targetLang = entry.getKey();
            List<Long> recipientIds = entry.getValue();
            if (recipientIds.isEmpty()) continue;

            // 전송할 텍스트 결정
            String contentToSend = determineContent(targetLang, savedMessage.getContent(), translatedContentsMap);

            // DTO 생성
            ChatMessageResponse messageResponse = new ChatMessageResponse(
                    savedMessage.getId(), savedMessage.getChatRoom().getId(), sender.getId(),
                    savedMessage.getContent(), contentToSend, savedMessage.getSentAt(),
                    sender.getFirstName(), sender.getLastName(), userImageUrl, MessageType.TEXT
            );

            // 비동기 전송 (New Transaction)
            CompletableFuture<Void> future = CompletableFuture.runAsync(() ->
                    chatSummaryService.sendSummaryToRecipientsInNewTx(messageResponse, recipientIds)
            );
            futures.add(future);
        }

        // 모든 전송 작업이 끝날 때까지 대기
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private String determineContent(String targetLang, String originalContent, Map<String, String> translatedMap) {
        if ("SELF".equals(targetLang) || "NONE".equals(targetLang)) {
            return originalContent; // 원문
        }
        return translatedMap.getOrDefault(targetLang, originalContent); // 번역문 (없으면 원문)
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
                        return mapToResponse(message, translatedContent, finalProfileMap);
                    }).collect(Collectors.toList());
        } else {
            Map<Long, String> finalProfileMap = profileMap;
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

        // 관리자에 의해 삭제된 경우
        if ("BLOCKED_MEDIA".equals(finalContent)) {
            finalContent = "관리자에 의해 삭제된 이미지입니다.";
            finalType = MessageType.TEXT;
            translatedContent = null;
        }
        // 그 외 이미지
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
            List<String> originalContents = messages.stream()
                    .map(ChatMessage::getContent)
                    .collect(Collectors.toList());

            // 검색된 결과(예: 5개)만 번역하므로 매우 빠르고 비용이 적음
            List<String> translatedContents = translationService.translateMessages(originalContents, targetLanguage);

            List<ChatMessage> finalMessages = messages;
            return IntStream.range(0, messages.size())
                    .mapToObj(i -> {
                        ChatMessage message = finalMessages.get(i);
                        String translatedContent = translatedContents.get(i);
                        return mapToResponse(message, translatedContent, profileMap); // 1단계의 mapToResponse 재사용
                    })
                    .sorted(Comparator.comparing(ChatMessageResponse::sentAt, Comparator.reverseOrder()))
                    .collect(Collectors.toList());
        } else {
            return messages.stream()
                    .map(m -> mapToResponse(m, null, profileMap)) // 1단계의 mapToResponse 재사용
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
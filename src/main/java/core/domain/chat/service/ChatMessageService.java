package core.domain.chat.service;

import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatMessageTranslation;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatMessageTranslationRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.notification.dto.NotificationBulkEvent;
import core.domain.user.entity.BlockUser;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.UserRepository;
import core.domain.user.service.UserRoleDetectService;
import core.domain.aiuser.dto.MessageCreatedEvent;
import core.global.entity.image.dto.ImageModerationEvent;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.impl.S3ImageStorageClient;
import core.global.enums.NotificationType;
import core.global.enums.chat.ChatParticipantStatus;
import core.global.enums.chat.MessageType;
import core.global.enums.common.ImageType;
import core.global.enums.errorcode.ChatErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import core.global.metrics.ChatMetrics;
import core.domain.admin.service.PerspectiveService;
import core.global.redis.service.RedisService;
import core.global.service.TranslationService;
import core.global.websocket.config.StompChannelInterceptor;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
    private final ChatMessageTranslationRepository chatMessageTranslationRepository;
    private final FastSocketSender fastSocketSender;

    // Service
    private final UserRoleDetectService userRoleDetectService;
    private final PerspectiveService perspectiveService;
    private final ChatMemberService chatMemberService;
    private final ChatSummaryService chatSummaryService;
    private final RedisService redisService;

    private final S3Presigner s3Presigner;
    private final ApplicationEventPublisher eventPublisher;
    private final ChatMetrics chatMetrics;
    private final ChatTranslationService chatTranslationService;
    private final TranslationService translationService;
    private final S3Client s3Client;
    private final S3ImageStorageClient s3ImageStorageClient;

    @Value("${cdn.base-url}")
    private String cdnBaseUrl;

    @Value("${ncp.s3.bucket}")
    private String bucketName;



    @Transactional
    public void processAndSendChatMessage(SendMessageRequest req) {
        try {
            // 1. 데이터베이스 및 기본 객체 준비
            ChatMessage savedMessage = saveMessage(req.roomId(), req.senderId(), req.content());
            ChatRoom chatRoom = fetchChatRoomWithParticipants(req.roomId());
            User sender = savedMessage.getSender();

            handleBusinessRules(chatRoom, savedMessage);


            ChatRecipientContext context = prepareRecipientContext(chatRoom, sender, savedMessage);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            Map<String, String> dbTranslations = new ConcurrentHashMap<>();      // 진짜 번역 성공해서 DB에 저장할 용도
            Map<String, String> payloadTranslations = new ConcurrentHashMap<>(); // 소켓으로 쏠 용도 (성공 번역 + 실패 땜빵 포함)
            String senderLang = sender.getLanguage();

            for (String lang : context.onlineMap().keySet()) {
                if (lang == null || lang.equals("NONE") || lang.equals(senderLang)) {
                    continue;
                }

                CompletableFuture<Void> future = chatTranslationService
                        .translateAndCache(savedMessage.getId(), savedMessage.getContent(), lang)
                        // 2. 성공 시: 양쪽 맵에 모두 담는다.
                        .thenAccept(translatedText -> {
                            dbTranslations.put(lang, translatedText);
                            payloadTranslations.put(lang, translatedText);
                        })
                        .orTimeout(2, TimeUnit.SECONDS)
                        // 3. 실패 시: 전송용(payload) 맵에만 원문을 땜빵한다! DB 맵에는 넣지 않음!
                        .exceptionally(ex -> {
                            payloadTranslations.put(lang, savedMessage.getContent());
                            return null;
                        });

                futures.add(future);
            }

            if (!futures.isEmpty()) {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }

            if (!dbTranslations.isEmpty()) {
                registerTranslationStorage(savedMessage.getId(), dbTranslations);
            }

            ChatMessageResponse baseResponse = buildBaseMessageResponse(
                    savedMessage,
                    getUserProfileImage(sender.getId())
            );

            ChatRoomSummaryResponse roomSummary = buildCommonRoomSummary(chatRoom, savedMessage);

            // 5. 소켓 전송(이벤트 발행): 땜빵이 포함된 '전송용(payloadTranslations)'을 실어 보낸다!
            eventPublisher.publishEvent(new MessageSentEvent(
                    baseResponse,
                    context.onlineMap(),
                    context.pushIds(),
                    payloadTranslations, // <--- 여기 주목
                    roomSummary
            ));
        } catch (Exception e) {
            log.error("❌ [ChatProcess Failed] roomId: {}, senderId: {}", req.roomId(), req.senderId(), e);
            throw e;
        }
    }
    private void handleBusinessRules(ChatRoom chatRoom, ChatMessage message) {
        if (Boolean.FALSE.equals(chatRoom.getIsGroup())) {
            reviveParticipantsIfDm(chatRoom);
        }
        runSpamCheckAsync(message);
    }

    private ChatRecipientContext prepareRecipientContext(ChatRoom chatRoom, User sender, ChatMessage message) {
        List<Long> blockedIds = getBlockedUserIds(sender.getId());

        Map<String, List<Long>> onlineMap = groupRecipientsByLanguage(chatRoom, sender, blockedIds, message.getId());

        List<Long> pushIds = getAllActiveParticipantsExceptSender(chatRoom, blockedIds, sender.getId());


        Map<String, String> translations = Collections.emptyMap();
        if (message.getMessageType() == MessageType.TEXT && !onlineMap.isEmpty()) {
            translations = executePureParallelTranslations(message.getContent(), onlineMap.keySet());
        }

        return new ChatRecipientContext(onlineMap, pushIds, translations);
    }



    private void registerTranslationStorage(Long messageId, Map<String, String> translations) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                translations.forEach((lang, content) ->
                        chatTranslationService.saveTranslationAsync(messageId, lang, content)
                );
            }
        });
    }

    private ChatRoomSummaryResponse buildCommonRoomSummary(ChatRoom chatRoom, ChatMessage message) {
        return new ChatRoomSummaryResponse(
                chatRoom.getId(), chatRoom.getRoomName(), message.getContent(),
                message.getSentAt(), null, 0, chatRoom.getParticipants().size()
        );
    }

    private record ChatRecipientContext(
            Map<String, List<Long>> onlineMap,
            List<Long> pushIds,
            Map<String, String> translations
    ) {}
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

    private ChatMessageResponse buildBaseMessageResponse(ChatMessage message, String userImageUrl) {
        User sender = message.getSender();
        return new ChatMessageResponse(
                message.getId(),
                message.getChatRoom().getId(),
                sender.getId(),
                message.getContent(),
                null,
                message.getSentAt(),
                sender.getFirstName(),
                sender.getLastName(),
                userImageUrl,
                message.getMessageType(),
                null,
                null
        );
    }


    /**
     * 최적화 버전
     * 세션에 참가한 참가자들만 발송
     */
    private Map<String, List<Long>> groupRecipientsByLanguage(ChatRoom chatRoom, User sender, List<Long> blockedUserIds, Long messageId) {
        Map<String, List<Long>> recipientsByLang = new HashMap<>();


        Set<String> activeUserStrIds = redisService.getSetElements(StompChannelInterceptor.ACTIVE_USERS_KEY);

        Set<Long> activeUserIds = activeUserStrIds.stream()
                .map(Long::valueOf)
                .collect(Collectors.toSet());

        for (ChatParticipant p : chatRoom.getParticipants()) {
            User recipient = p.getUser();
            Long rid = recipient.getId();

            if (blockedUserIds.contains(rid)) continue;
            if (p.getStatus() != ChatParticipantStatus.ACTIVE) continue;

            if (!activeUserIds.contains(rid) && !rid.equals(sender.getId())) {
                continue;
            }

            String lang = (p.isTranslateEnabled() && recipient.getTranslateLanguage() != null)
                    ? recipient.getTranslateLanguage()
                    : "NONE";

            recipientsByLang.computeIfAbsent(lang, k -> new ArrayList<>()).add(rid);
        }

        return recipientsByLang;
    }
    /**
     * [역최적화] 수신자를 온라인 여부와 상관없이 모든 ACTIVE 참가자로 그룹핑합니다.
    private Map<String, List<Long>> groupRecipientsByLanguage(ChatRoom chatRoom, User sender, List<Long> blockedUserIds, Long messageId) {
        Map<String, List<Long>> recipientsByLang = new HashMap<>();

        for (ChatParticipant p : chatRoom.getParticipants()) {
            User recipient = p.getUser();
            Long rid = recipient.getId();

            if (blockedUserIds.contains(rid)) continue;
            if (p.getStatus() != ChatParticipantStatus.ACTIVE) continue;
            String lang = (p.isTranslateEnabled() && recipient.getTranslateLanguage() != null)
                    ? recipient.getTranslateLanguage()
                    : "NONE";

            recipientsByLang.computeIfAbsent(lang, k -> new ArrayList<>()).add(rid);
        }

        return recipientsByLang;
    }*/

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
    /**
     * 필요한 언어들에 대해 병렬로 번역을 수행합니다.
     */
    /**
     * [신규] 저장 없이 순수하게 번역 API만 호출하여 결과를 리턴합니다.
     */
    private Map<String, String> executePureParallelTranslations(String originalContent, Set<String> targetLanguages) {
        Map<String, String> resultMap = new ConcurrentHashMap<>();

        List<String> languagesToTranslate = targetLanguages.stream()
                .filter(lang -> !"SELF".equals(lang) && !"NONE".equals(lang))
                .distinct()
                .toList();

        List<CompletableFuture<Void>> futures = languagesToTranslate.stream()
                .map(lang -> CompletableFuture.runAsync(() -> {
                    List<String> res = translationService.translateMessages(List.of(originalContent), lang);
                    if (!res.isEmpty()) {
                        resultMap.put(lang, res.get(0));
                    }
                }))
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(2, TimeUnit.SECONDS); // 무한 대기 방지
        } catch (Exception e) {
            log.error("번역 시간 초과 혹은 에러");
        }

        return resultMap;
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getMessages(Long roomId, Long userId, Long lastMessageId) {
        // 1. 참여자 검증
        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_CHAT_PARTICIPANT));

        userRoleDetectService.isProfileSetUpUser(participant.getUser());

        boolean needsTranslation = participant.isTranslateEnabled();
        String targetLanguage = participant.getUser().getTranslateLanguage();

        // 2. 원본 메시지 조회
        List<ChatMessage> rawMessages = getRawMessages(roomId, userId, lastMessageId);

        // 3. 차단 유저 필터링 (Stream -> For Loop)
        List<Long> blockedIds = getBlockedUserIds(userId);
        List<ChatMessage> validMessages = new ArrayList<>();

        if (blockedIds.isEmpty()) {
            validMessages = rawMessages;
        } else {
            for (ChatMessage msg : rawMessages) {
                // 보낸 사람이 차단 목록에 없으면 추가
                if (!blockedIds.contains(msg.getSender().getId())) {
                    validMessages.add(msg);
                }
            }
        }

        // 4. 프로필 이미지 조회 (N+1 방지)
        Set<Long> senderIds = new HashSet<>();
        for (ChatMessage msg : validMessages) {
            senderIds.add(msg.getSender().getId());
        }

        Map<Long, String> profileMap = new HashMap<>();
        if (!senderIds.isEmpty()) {
            List<Image> images = imageRepository.findAllByImageTypeAndRelatedIdInOrderByOrderIndexAsc(ImageType.USER, new ArrayList<>(senderIds));
            for (Image img : images) {
                // 중복 시 기존 것 유지 (기존 로직 따름)
                profileMap.putIfAbsent(img.getRelatedId(), img.getUrl());
            }
        }

        // 5. 번역 데이터 조회
        Map<Long, String> translatedMap = Collections.emptyMap();
        if (needsTranslation && targetLanguage != null && !targetLanguage.isEmpty()) {
            List<ChatMessage> textMessages = new ArrayList<>();
            for (ChatMessage msg : validMessages) {
                if (msg.getMessageType() == MessageType.TEXT) {
                    textMessages.add(msg);
                }
            }
            // 번역 서비스 호출
            translatedMap = chatTranslationService.getTranslatedMessages(textMessages, targetLanguage);
        }

        // 6. 응답 변환 (For Loop)
        List<ChatMessageResponse> responseList = new ArrayList<>();
        for (ChatMessage msg : validMessages) {
            String translatedContent = null;
            if (msg.getMessageType() == MessageType.TEXT) {
                translatedContent = translatedMap.get(msg.getId());
            }

            // DTO 변환 헬퍼 호출
            responseList.add(mapToResponse(msg, translatedContent, profileMap));
        }

        return responseList;
    }

    /**
     * [Helper] 엔티티 -> 응답 DTO 변환
     * - BLOCKED_MEDIA 처리
     * - 이미지/비디오 URL 생성
     * - 썸네일 생성
     */
    private ChatMessageResponse mapToResponse(ChatMessage message, String translatedContent, Map<Long, String> profileMap) {
        User sender = message.getSender();
        // 프로필 이미지 URL 가져오기 (없으면 null)
        String senderImg = profileMap.get(sender.getId());

        String originContent = message.getContent();
        MessageType messageType = message.getMessageType();

        String mediaUrl = null;
        String thumbnailUrl = null;

        // 1. 차단된 미디어 처리
        if ("BLOCKED_MEDIA".equals(originContent)) {
            originContent = "관리자에 의해 삭제된 이미지입니다.";
            messageType = MessageType.TEXT; // 클라이언트가 텍스트로 렌더링하도록 변경
            translatedContent = null;       // 번역 제거
        }
        // 2. 정상 미디어(이미지/비디오) 처리
        else if (messageType == MessageType.IMAGE || messageType == MessageType.VIDEO) {
            // DB에 경로만 저장되어 있다면 Full URL 생성
            if (!originContent.startsWith("http")) {
                mediaUrl = cdnBaseUrl + "/" + originContent;
            } else {
                mediaUrl = originContent;
            }

            if (messageType == MessageType.VIDEO) {
                if (!originContent.startsWith("http")) {
                    String thumbKey = originContent.substring(0, originContent.lastIndexOf('.')) + ".jpg";
                    thumbnailUrl = cdnBaseUrl + "/" + thumbKey;
                }
                originContent = "video"; // 미리보기용 텍스트
            } else {
                originContent = "picture";   // 미리보기용 텍스트
            }
        }

        return new ChatMessageResponse(
                message.getId(),
                message.getChatRoom().getId(),
                sender.getId(),
                originContent,      // 텍스트 본문 or "사진"/"동영상" or "차단메시지"
                translatedContent,  // 번역본
                message.getSentAt(),
                sender.getFirstName(),
                sender.getLastName(),
                senderImg,          // 프로필 이미지
                messageType,        // (BLOCKED 된 경우 TEXT로 변경됨)
                mediaUrl,           // [NEW] 실제 미디어 URL
                thumbnailUrl        // [NEW] 썸네일 URL
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
    private List<Long> getAllActiveParticipantsExceptSender(ChatRoom chatRoom, List<Long> blockedUserIds, Long senderId) {
        List<Long> targets = new ArrayList<>();
        for (ChatParticipant p : chatRoom.getParticipants()) {
            Long rid = p.getUser().getId();

            // 차단 유저 제외 및 보낸 사람 본인 제외
            if (blockedUserIds.contains(rid)) continue;
            if (rid.equals(senderId)) continue;

            // 방에서 나가지 않은(ACTIVE) 상태의 유저라면 전부 추가
            if (p.getStatus() == ChatParticipantStatus.ACTIVE) {
                targets.add(rid);
            }
        }
        return targets;
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
        // 1. [검증] 파일 존재 여부 확인
        validateObjectStorageFile(req.mediaKey());
        if (req.messageType() == MessageType.VIDEO && req.thumbnailKey() != null) {
            validateObjectStorageFile(req.thumbnailKey());
        }

        // 2. 데이터 조회
        ChatRoom chatRoom = chatRoomRepository.findById(req.roomId())
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        User sender = userRepository.findById(req.senderId())
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        // 3. 메시지 저장 및 읽음 처리
        ChatMessage savedMessage = new ChatMessage(chatRoom, sender, req.mediaKey(), req.messageType());
        chatMessageRepository.save(savedMessage);
        chatRoom.updateLastMessageSentAt(savedMessage.getSentAt());
        chatRoom.incrementMessageCount();
        saveMediaToImageTable(savedMessage, req);

        chatParticipantRepository.findByChatRoomIdAndUserId(req.roomId(), req.senderId())
                .ifPresent(participant -> participant.setLastReadMessageId(savedMessage.getId()));

        // 4. [수신자 결정] 차단 제외하고 나 빼고 전원 (필터링 없이 이 명단 그대로 다 쏩니다)
        List<Long> blockedUserIds = getBlockedUserIds(sender.getId());
        List<Long> targetIds = chatRoom.getParticipants().stream()
                .map(p -> p.getUser().getId())
                .filter(id -> !id.equals(sender.getId()) && !blockedUserIds.contains(id))
                .toList();

        // 리스너 규격에 맞게 Map으로 감싸기만 함 (필터링 X)
        Map<String, List<Long>> recipientsMap = targetIds.isEmpty() ?
                Map.of() : Map.of("NONE", targetIds);

        // 5. 응답 DTO 및 요약본 생성
        String mediaUrl = cdnBaseUrl + "/" + savedMessage.getContent();
        String thumbnailUrl = (req.thumbnailKey() != null) ? cdnBaseUrl + "/" + req.thumbnailKey() : null;
        String senderProfileUrl = getUserProfileImage(sender.getId());

        ChatMessageResponse messageResponse = new ChatMessageResponse(
                savedMessage.getId(), chatRoom.getId(), sender.getId(),
                (req.messageType() == MessageType.IMAGE ? "사진" : "동영상"), null,
                savedMessage.getSentAt(), sender.getFirstName(), sender.getLastName(),
                senderProfileUrl, savedMessage.getMessageType(), mediaUrl, thumbnailUrl
        );

        ChatRoomSummaryResponse summary = buildChatRoomSummaryResponse(chatRoom.getId(), sender.getId());

        // 6. [전송] 이벤트 발행 (targetIds 명단 그대로 웹소켓/푸시 둘 다 발송)
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventPublisher.publishEvent(new MessageSentEvent(
                        messageResponse, // 1. 기본 정보
                        recipientsMap,   // 2. 웹소켓 대상 (targetIds 전체)
                        targetIds,       // 3. 푸시 대상 (targetIds 전체)
                        Map.of(),        // 4. 번역 없음
                        summary          // 5. 요약본
                ));
            }
        });
    }
    private void validateObjectStorageFile(String fileKey) {
        try {
            // S3 클라이언트로 파일 메타데이터 조회 (없으면 예외 발생)
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(fileKey).build());
        } catch (NoSuchKeyException e) {
            throw new BusinessException(ChatErrorCode.FILE_UPLOAD_FAILED); // "파일이 없습니다"
        }
    }

    private void saveMediaToImageTable(ChatMessage message, SendMediaMessageRequest req) {
        if (req.messageType() == MessageType.IMAGE) {
            String fullUrl = cdnBaseUrl + "/" + req.mediaKey();
            Image image = Image.of(ImageType.CHAT_MEDIA, message.getId(), fullUrl, 0);
            imageRepository.save(image);

            // 이미지 검열 요청 (비동기)
            eventPublisher.publishEvent(new ImageModerationEvent(image.getId(), req.mediaKey()));

        } else if (req.messageType() == MessageType.VIDEO && req.thumbnailKey() != null) {
            // 동영상은 썸네일을 Image 테이블에 저장
            String thumbUrl = cdnBaseUrl + "/" + req.thumbnailKey();
            // ImageType.CHAT_THUMBNAIL 등을 추가해서 구분하면 더 좋음 (없으면 CHAT_MEDIA 사용)
            Image thumbnail = Image.of(ImageType.CHAT_MEDIA, message.getId(), thumbUrl, 0);
            imageRepository.save(thumbnail);
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

    public PresignedUrlResponse generateChatPresignedUrl(Long chatroomId, String fileName, MessageType fileType) {
        // 1. UUID를 한 번만 생성 (동영상과 썸네일이 공유!)
        String commonUuid = UUID.randomUUID().toString();

        // 2. 메인 파일 Key 생성 (예: chats/1/uuid-video.mp4)
        String fileKey = "chats/" + chatroomId + "/" + commonUuid + "-" + fileName;

        // 3. 메인 파일 URL 생성
        String mainUrl = createPresignedUrl(fileKey);

        String thumbnailKey = null;
        String thumbnailUrl = null;

        // 4. 동영상인 경우에만 썸네일 URL 추가 생성
        if (fileType == MessageType.VIDEO) {
            int lastDotIndex = fileKey.lastIndexOf('.');
            if (lastDotIndex != -1) {
                thumbnailKey = fileKey.substring(0, lastDotIndex) + ".jpg";
            } else {
                thumbnailKey = fileKey + ".jpg";
            }

            thumbnailUrl = createPresignedUrl(thumbnailKey);
        }

        return new PresignedUrlResponse(mainUrl, fileKey, thumbnailUrl, thumbnailKey);
    }

    // [리팩토링] 중복 코드를 줄이기 위한 헬퍼 메서드
    private String createPresignedUrl(String key) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(15))
                .putObjectRequest(putObjectRequest)
                .build();

        return s3Presigner.presignPutObject(presignRequest).url().toString();
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
        // 1. 참여자 검증
        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_CHAT_PARTICIPANT));

        // 2. 메시지 조회 (Cursor Paging)
        List<ChatMessage> older = chatMessageRepository.findTop20ByChatRoomIdAndIdLessThanOrderByIdDesc(roomId, targetMessageId);
        Collections.reverse(older);

        ChatMessage target = chatMessageRepository.findById(targetMessageId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.MESSAGE_NOT_FOUND));

        List<ChatMessage> newer = chatMessageRepository.findTop20ByChatRoomIdAndIdGreaterThanOrderByIdAsc(roomId, targetMessageId);

        List<ChatMessage> combined = new ArrayList<>();
        combined.addAll(older);
        combined.add(target);
        combined.addAll(newer);

        // 3. [안전 장치] 데이터 추출 (삭제된 유저 방어 로직)
        Set<Long> senderIds = new HashSet<>();
        for (ChatMessage msg : combined) {
            try {
                // 🚨 핵심: 여기서 삭제된 유저의 프록시를 건드리면 EntityNotFound 발생
                // 예외를 잡아서 서버가 죽지 않게 방어함
                User sender = msg.getSender();
                if (sender != null) {
                    senderIds.add(sender.getId()); // ID 접근 시 실제 DB 확인
                }
            } catch (EntityNotFoundException e) {
                // 삭제된 유저(고아 데이터)입니다. 로그만 남기고 무시합니다.
                // senderIds에 추가되지 않으므로 아래 로직에서 'Unknown' 처리됩니다.
            }
        }

        // 4. [성능 최적화] 유저 및 프로필 이미지 일괄 조회 (N+1 방지)
        Map<Long, User> senderMap = new HashMap<>();
        Map<Long, String> profileImageMap = new HashMap<>();

        if (!senderIds.isEmpty()) {
            // 유저 조회
            List<User> users = userRepository.findAllById(senderIds);
            for (User u : users) {
                senderMap.put(u.getId(), u);
            }

            // 프로필 이미지 조회 (Bulk)
            List<Image> images = imageRepository.findAllByImageTypeAndRelatedIdInOrderByOrderIndexAsc(ImageType.USER, new ArrayList<>(senderIds));
            for (Image img : images) {
                profileImageMap.putIfAbsent(img.getRelatedId(), img.getUrl());
            }
        }

        // 5. 번역 처리
        Map<Long, String> translatedMap = Collections.emptyMap();
        if (participant.isTranslateEnabled() && participant.getUser().getTranslateLanguage() != null) {
            translatedMap = chatTranslationService.getTranslatedMessages(combined, participant.getUser().getTranslateLanguage());
        }

        // 6. 응답 변환
        List<ChatMessageResponse> responseList = new ArrayList<>();
        for (ChatMessage msg : combined) {
            Long senderId = null;
            try {
                // 여기서도 getSender() 호출 시 에러 날 수 있으므로 방어
                if (msg.getSender() != null) {
                    senderId = msg.getSender().getId();
                }
            } catch (EntityNotFoundException e) {
            }

            User sender = (senderId != null) ? senderMap.get(senderId) : null;
            String profileUrl = (senderId != null) ? profileImageMap.get(senderId) : null;
            String translated = translatedMap.get(msg.getId());

            responseList.add(mapToResponse(msg, sender, profileUrl, translated));
        }

        return responseList;
    }

    /**
     * [Helper] 안전한 DTO 변환기
     * - 이제 이 안에서는 DB 조회를 하지 않습니다.
     */
    private ChatMessageResponse mapToResponse(ChatMessage msg, User sender, String profileImageUrl, String translatedContent) {
        String content = msg.getContent();
        String mediaUrl = null;
        String thumbnailUrl = null;

        // --- 미디어 처리 ---
        if (msg.getMessageType() == MessageType.IMAGE || msg.getMessageType() == MessageType.VIDEO) {
            mediaUrl = s3ImageStorageClient.generatePublicUrl(msg.getContent());
            if (msg.getMessageType() == MessageType.VIDEO) {
                thumbnailUrl = s3ImageStorageClient.generateThumbnailUrl(msg.getContent());
                content = "video";
            } else {
                content = "picture";
            }
        }

        // --- 보낸 사람 정보 (기본값 설정) ---
        String senderFirstName = "Unknown"; // 기본값: 알 수 없음
        String senderLastName = "";

        if (sender != null) {
            senderFirstName = sender.getFirstName();
            senderLastName = sender.getLastName();
        }

        // --- ID 안전 추출 ---
        Long msgSenderId = null;
        Long msgRoomId = msg.getChatRoom().getId();

        try {
            if (sender != null) msgSenderId = sender.getId();
        } catch (Exception e) { /* 무시 */ }

        return new ChatMessageResponse(
                msg.getId(),
                msgRoomId,
                msgSenderId,
                content,
                translatedContent,
                msg.getSentAt(),
                senderFirstName,
                senderLastName,
                profileImageUrl, // 미리 조회한 URL 사용
                msg.getMessageType(),
                mediaUrl,
                thumbnailUrl
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

    private String getPreviewContent(ChatMessage message) {
        if (message == null) {
            return "";
        }

        switch (message.getMessageType()) {
            case IMAGE:
                return "Sent a photo"; // 📷
            case VIDEO:
                return "Sent a video"; // 🎥
            case TEXT:
            default:
                return message.getContent();
        }

    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ChatRoomSummaryResponse buildChatRoomSummaryResponse(Long roomId, Long forUserId) {
        ChatRoom room = chatRoomRepository.findById(roomId).orElseThrow();
        ChatMessage lastMsg = chatMessageRepository.findTopByChatRoomIdOrderBySentAtDesc(roomId).orElse(null);
        String lastContent = (lastMsg != null) ? getPreviewContent(lastMsg) : "start to talk";

        Instant lastTime = (room.getLastMessageSentAt() != null)
                ? room.getLastMessageSentAt()
                : room.getCreatedAt();

        int unread = countUnreadMessages(roomId, forUserId);

        String name = room.getRoomName();
        String img = null;

        if (!Boolean.TRUE.equals(room.getIsGroup())) {
            User opponent = room.getParticipants().stream()
                    .map(ChatParticipant::getUser)
                    .filter(u -> !u.getId().equals(forUserId))
                    .findFirst()
                    .orElse(null);

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

    private final TranslationService externalTranslationService;
    private final SimpMessagingTemplate messagingTemplate;
    @Transactional
    public void sendMessageBad(SendMessageRequest req) {
        // 1. 데이터 조회 (방, 참여자, 보낸 사람)
        ChatRoom chatRoom = chatRoomRepository.findChatRoomWithParticipantsAndUsers(req.roomId())
                .orElseThrow(() -> new RuntimeException("Room not found"));
        User sender = userRepository.findById(req.senderId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        // 2. 메시지 저장 및 즉시 반영
        ChatMessage message = new ChatMessage(chatRoom, sender, req.content());
        chatMessageRepository.save(message);
        chatMessageRepository.flush();

        // 3. 스팸 체크 (동기 지연 100ms)
        checkSpamSync(message);

        // 4. 수신자 언어별 그룹핑
        Map<String, List<ChatParticipant>> groupByLang = chatRoom.getParticipants().stream()
                .collect(Collectors.groupingBy(p -> {
                    String lang = p.getUser().getTranslateLanguage();
                    return (p.isTranslateEnabled() && lang != null) ? lang : "ORIGINAL";
                }));

        // 5. [추가] 알림 이벤트 발행을 위한 수신자 ID 추출
        List<Long> recipientIds = chatRoom.getParticipants().stream()
                .map(p -> p.getUser().getId())
                .filter(id -> !id.equals(sender.getId()))
                .toList();

        if (!recipientIds.isEmpty()) {
            eventPublisher.publishEvent(new NotificationBulkEvent(
                    recipientIds,
                    sender.getId(),
                    NotificationType.chat,
                    chatRoom.getId(),
                    message.getContent(),
                    chatRoom.getRoomName()
            ));
        }

        // 6. 웹소켓 전송 (기존 로직 유지)
        for (Map.Entry<String, List<ChatParticipant>> entry : groupByLang.entrySet()) {
            String lang = entry.getKey();
            if ("ORIGINAL".equals(lang)) continue;

            List<String> translatedResults = externalTranslationService.translateMessages(List.of(message.getContent()), lang);
            String translatedText = translatedResults.get(0);

            for (ChatParticipant p : entry.getValue()) {
                if (p.getUser().getId().equals(sender.getId())) continue;
                messagingTemplate.convertAndSend("/topic/user/" + p.getUser().getId() + "/messages", translatedText);
            }
        }

        List<ChatParticipant> originalGroup = groupByLang.getOrDefault("ORIGINAL", Collections.emptyList());
        for (ChatParticipant p : originalGroup) {
            if (p.getUser().getId().equals(sender.getId())) {
                p.setLastReadMessageId(message.getId());
            } else {
                messagingTemplate.convertAndSend("/topic/user/" + p.getUser().getId() + "/messages", req.content());
            }
        }
    }

    private void checkSpamSync(ChatMessage message) {
        try { Thread.sleep(100); } catch (InterruptedException e) { }
    }
    private String translateSync(String content, String lang) {
        try { Thread.sleep(200); } catch (InterruptedException e) { }
        return "[Translated to " + lang + "] " + content;
    }

    private void sendNotificationSync(User recipient, String message) {
        try { Thread.sleep(50); } catch (InterruptedException e) { }
    }
}
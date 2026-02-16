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
import core.global.enums.chat.ChatParticipantStatus;
import core.global.enums.chat.MessageType;
import core.global.enums.common.ImageType;
import core.global.enums.errorcode.ChatErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import core.global.metrics.ChatMetrics;
import core.domain.admin.service.PerspectiveService;
import core.global.service.TranslationService;
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
            // 1. 메시지 저장 및 필수 데이터 조회 (DB Insert)
            ChatMessage savedMessage = this.saveMessage(req.roomId(), req.senderId(), req.content());
            ChatRoom chatRoom = fetchChatRoomWithParticipants(req.roomId());
            chatRoom.updateLastMessageSentAt(savedMessage.getSentAt());

            User sender = savedMessage.getSender();

            // =================================================================
            // [NEW] 1:1 채팅(isGroup == false)이면 나간 사람 복구 (Rejoin)
            // =================================================================
            if (Boolean.FALSE.equals(chatRoom.getIsGroup())) {
                reviveParticipantsIfDm(chatRoom);
            }
            // 2. 비동기 스팸 체크 (Fire-and-Forget, 이건 상관없음)
            runSpamCheckAsync(savedMessage);

            // 3. 부가 정보 조회
            String userImageUrl = getUserProfileImage(sender.getId());
            List<Long> blockedUserIds = getBlockedUserIds(sender.getId());

            // 4. 수신자 그룹핑 (언어별)
            Map<String, List<Long>> recipientsByLang = groupRecipientsByLanguage(
                    chatRoom, sender, blockedUserIds, savedMessage.getId()
            );
            List<Long> allRecipientIds = getAllRecipientIds(recipientsByLang);

            // 5. 병렬 번역 실행 (저장 X, 메모리상에 결과만 보유)
            Map<String, String> finalTranslations = new HashMap<>();
            if (savedMessage.getMessageType() == MessageType.TEXT) {
                finalTranslations = executePureParallelTranslations(
                        savedMessage.getContent(), recipientsByLang.keySet()
                );
            }

            // 6. 트랜잭션 커밋 후 실행 (이벤트 발행 및 비동기 저장)
            final Long messageId = savedMessage.getId();
            final Map<String, String> translationsToSave = finalTranslations;
            final String userImg = userImageUrl;

            // 이벤트 발행 (기본 메시지 전송용)
            // -> 여기서 ChatEventListener.handleMessageSent가 호출됨
            ChatMessageResponse baseResponse = buildBaseMessageResponse(savedMessage, userImg);
            eventPublisher.publishEvent(new MessageCreatedEvent(baseResponse, allRecipientIds));

            // 커밋 후 동작: 번역 저장 및 언어별 전송
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // A. 번역 결과 DB 저장 (Async)
                    translationsToSave.forEach((lang, content) ->
                            chatTranslationService.saveTranslationAsync(messageId, lang, content)
                    );

                    // B. [언어별 전송] 여기가 중요합니다!
                    // 기본 메시지(MessageCreatedEvent)는 원문을 보내지만,
                    // 번역이 필요한 사용자들에게는 '번역된 버전'을 따로 쏴줘야 합니다.
                    executeParallelDispatchAfterCommit(recipientsByLang, translationsToSave, baseResponse);
                }
            });
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

    private ChatMessageResponse buildBaseMessageResponse(ChatMessage message, String userImageUrl) {
        User sender = message.getSender();
        return new ChatMessageResponse(
                message.getId(),
                message.getChatRoom().getId(),
                sender.getId(),
                message.getContent(),
                null, // 번역본은 필요 시 별도 세팅
                message.getSentAt(),
                sender.getFirstName(),
                sender.getLastName(),
                userImageUrl,
                message.getMessageType(),
                null, // 미디어 URL (텍스트 메시지 기준)
                null  // 썸네일 URL
        );
    }

    private List<Long> getAllRecipientIds(Map<String, List<Long>> recipientsByLang) {
        return recipientsByLang.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
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
                p.setLastReadMessageId(messageId);
            }
            if (p.getStatus() != ChatParticipantStatus.ACTIVE) {
                continue;
            }

            String lang = (p.isTranslateEnabled() && recipient.getTranslateLanguage() != null)
                    ? recipient.getTranslateLanguage()
                    : "NONE";

            recipientsByLang.computeIfAbsent(lang, k -> new ArrayList<>()).add(recipient.getId());
        }
        return recipientsByLang;
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

        // 외부 번역 서비스 호출 (병렬)
        List<CompletableFuture<Void>> futures = languagesToTranslate.stream()
                .map(lang -> CompletableFuture.runAsync(() -> {
                    // 이 내부의 sleep(200ms)은 가상 스레드를 'Pinn' 시키지 않고
                    // 물리 스레드를 반납하게 설계되어야 함
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


   /* [Zero-Copy 최적화 적용]
            * 1. N+1 DB 조회(Race Condition Check) 제거 -> 서버 멈춤 현상 해결
 * 2. 언어별(Language)로 DTO를 1번만 생성 -> 1,000번 반복되는 JSON 변환 제거
 * 3. executeParallelDispatchAfterCommit 메서드 시그니처 유지
 */
    private void executeParallelDispatchAfterCommit(
            Map<String, List<Long>> recipientsByLang,
            Map<String, String> translations, // 번역 결과 Map ("en": "Hello", "ko": "안녕")
            ChatMessageResponse baseResponse  // 기본 메시지 정보 (원문 포함)
    ) {
        // 언어별 그룹 루프 (최대 3~5회 반복 - CPU 부하 거의 없음)
        recipientsByLang.forEach((lang, recipients) -> {
            if (recipients == null || recipients.isEmpty()) return;

            // 1. 번역문(targetContent) 결정
            // "NONE"(번역안함)이거나 "SELF"(나)인 경우 null, 그 외에는 번역맵에서 가져옴
            String targetContent = null;
            if (!"NONE".equals(lang) && !"SELF".equals(lang)) {
                targetContent = translations.get(lang);
            }

            // 2. 언어별 맞춤 DTO 생성 (메모리 연산: 아주 빠름)
            // 원문(originContent)은 유지하고, 번역문(targetContent)만 갈아끼웁니다.
            ChatMessageResponse personalizedMsg = new ChatMessageResponse(
                    baseResponse.id(),
                    baseResponse.roomId(),
                    baseResponse.senderId(),
                    baseResponse.originContent(), // 원문 유지
                    targetContent,                // 번역문 (있으면 넣고, 없으면 null)
                    baseResponse.sentAt(),
                    baseResponse.senderFirstName(),
                    baseResponse.senderLastName(),
                    baseResponse.senderImageUrl(), // DTO 필드명 확인 필요 (senderImageUrl vs userImageUrl)
                    baseResponse.messageType(),
                    baseResponse.mediaUrl(),
                    baseResponse.thumbnailUrl()
            );

            // 3. 웹소켓 전송용 래퍼 생성
            // 프론트엔드가 받는 JSON 형태: { "type": "NEW_MESSAGE", "data": { ... } }
            TypedWebSocketResponse<ChatMessageResponse> payload =
                    new TypedWebSocketResponse<>("NEW_MESSAGE", personalizedMsg);

            // 4. [핵심] Zero-Copy 전송
            // - 여기서 JSON 변환은 딱 1번만 일어납니다.
            // - 생성된 byte[]를 N명(recipients)에게 쫙 뿌립니다.
            String destinationSuffix = "/" + baseResponse.roomId() + "/messages";
            fastSocketSender.sendToUsersFast(recipients, destinationSuffix, payload);
        });
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
        // 1. [검증] 실제 스토리지에 파일이 존재하는지 확인 (보안)
        validateObjectStorageFile(req.mediaKey());
        if (req.messageType() == MessageType.VIDEO && req.thumbnailKey() != null) {
            validateObjectStorageFile(req.thumbnailKey()); // 썸네일도 확인
        }

        ChatRoom chatRoom = chatRoomRepository.findById(req.roomId())
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        User sender = userRepository.findById(req.senderId())
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        // 2. 메시지 저장 (DB에는 파일 Key만 저장)
        ChatMessage savedMessage = new ChatMessage(chatRoom, sender, req.mediaKey(), req.messageType());
        chatMessageRepository.save(savedMessage);

        chatRoom.updateLastMessageSentAt(savedMessage.getSentAt());

        // 3. Image 테이블 저장 (사진 및 동영상 썸네일 관리용)
        saveMediaToImageTable(savedMessage, req);

        // 4. 읽음 처리
        chatParticipantRepository.findByChatRoomIdAndUserId(req.roomId(), req.senderId())
                .ifPresent(participant -> participant.setLastReadMessageId(savedMessage.getId()));

        // 5. 수신자 계산 (차단 로직 포함)
        List<Long> recipientIds = chatRoom.getParticipants().stream()
                .map(ChatParticipant::getUser)
                .filter(user -> !blockRepository.existsBlock(user.getId(), sender.getId())
                                && !blockRepository.existsBlock(sender.getId(), user.getId())) // 양방향 체크
                .map(User::getId)
                .toList();

        // 6. 응답 DTO 생성
        // 6-1. URL 생성 (s3ImageStorageClient를 주입받아 쓰는 것을 권장하지만, 기존 로직 유지 시 아래처럼 사용)
        // UrlUtil.buildCdnUrlFromKey(cdnBaseUrl, key)를 사용하는 것이 더 안전합니다.
        String mediaUrl = cdnBaseUrl + "/" + savedMessage.getContent();
        String thumbnailUrl = (req.thumbnailKey() != null) ? cdnBaseUrl + "/" + req.thumbnailKey() : null;

        // 6-2. 프로필 이미지 조회
        // (참고: Image 엔티티에 저장된 값이 Key라면 URL 변환 필요, 이미 URL이면 그대로 사용)
        String senderProfileUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, sender.getId())
                .map(Image::getUrl)
                .orElse(null);

        // 6-3. 텍스트 대체 문구 설정
        String originContentText = (req.messageType() == MessageType.IMAGE) ? "사진" : "동영상";

        // 6-4. DTO 생성 (Record 순서 주의)
        ChatMessageResponse messageResponse = new ChatMessageResponse(
                savedMessage.getId(),           // id
                chatRoom.getId(),               // roomId
                sender.getId(),                 // senderId
                originContentText,              // originContent ("사진" or "동영상")
                null,                           // targetContent (번역 없음)
                savedMessage.getSentAt(),       // sentAt
                sender.getFirstName(),          // senderFirstName
                sender.getLastName(),           // senderLastName
                senderProfileUrl,               // senderImageUrl
                savedMessage.getMessageType(),  // messageType
                mediaUrl,                       // mediaUrl [NEW]
                thumbnailUrl                    // thumbnailUrl [NEW]
        );

        // 7. [비동기 전송] 트랜잭션 커밋 후 이벤트 발행
        ChatRoomSummaryResponse summary = buildChatRoomSummaryResponse(chatRoom.getId(), sender.getId());

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 별도 스레드에서 웹소켓 전송
                eventPublisher.publishEvent(new MessageSentEvent(messageResponse, recipientIds, summary));
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
}
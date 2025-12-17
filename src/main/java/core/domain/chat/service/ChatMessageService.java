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
import core.global.entity.image.service.impl.S3ImageStorageClient;
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
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
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
import java.util.function.Function;
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
            User sender = savedMessage.getSender();

            // 2. 비동기 스팸 체크 (Fire-and-Forget, 이건 상관없음)
            runSpamCheckAsync(savedMessage);

            // 3. 부가 정보 조회
            String userImageUrl = getUserProfileImage(sender.getId());
            List<Long> blockedUserIds = getBlockedUserIds(sender.getId());

            // 4. 수신자 그룹핑
            Map<String, List<Long>> recipientsByLang = groupRecipientsByLanguage(
                    chatRoom, sender, blockedUserIds, savedMessage.getId()
            );

            // 5. [수정됨] 병렬 번역 실행 (여기서는 저장하지 않고 '결과값'만 받아옵니다!)
            Map<String, String> translatedContentsMap = new HashMap<>();
            if (savedMessage.getMessageType() == MessageType.TEXT) {
                // 주의: 여기서 내부적으로 save를 호출하던 translateAndCache 대신,
                // 순수하게 번역만 해오는 메서드를 호출하거나, 로직을 분리해야 합니다.
                translatedContentsMap = executePureParallelTranslations(
                        savedMessage.getContent(), recipientsByLang.keySet()
                );
            }

            // 6. [수정됨] 트랜잭션 커밋 후 실행 (저장 + 전송)
            // 이 시점에 DB에는 message가 확실히 있습니다.
            final Long messageId = savedMessage.getId();
            final Map<String, String> finalTranslations = translatedContentsMap;

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // A. 번역 결과 DB 저장 (이제 안전함!)
                    finalTranslations.forEach((lang, content) ->
                            chatTranslationService.saveTranslationAsync(messageId, lang, content)
                    );

                    // B. 메시지 전송 (기존 executeParallelDispatch 로직 이동)
                    executeParallelDispatchAfterCommit(
                            recipientsByLang, finalTranslations, savedMessage, userImageUrl
                    );
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
                    try {
                        List<String> res = translationService.translateMessages(List.of(originalContent), lang);
                        if (!res.isEmpty()) {
                            resultMap.put(lang, res.get(0));
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
    /**
     * [수정됨] 커밋 후 실행되므로 TransactionSynchronizationManager 등록 부분 제거
     */
    private void executeParallelDispatchAfterCommit(
            Map<String, List<Long>> recipientsByLang,
            Map<String, String> translatedContentsMap,
            ChatMessage savedMessage,
            String userImageUrl
    ) {
        // 1. 데이터 추출 (이미 커밋된 상태라 Lazy Loading 걱정 덜하지만, 안전하게 ID 등 사용)
        Long messageId = savedMessage.getId();
        Long roomId = savedMessage.getChatRoom().getId();
        Long senderId = savedMessage.getSender().getId();
        String content = savedMessage.getContent();
        Instant sentAt = savedMessage.getSentAt();
        String senderFirstName = savedMessage.getSender().getFirstName();
        String senderLastName = savedMessage.getSender().getLastName();
        MessageType msgType = savedMessage.getMessageType();

        // 2. 전송
        for (Map.Entry<String, List<Long>> entry : recipientsByLang.entrySet()) {
            String targetLang = entry.getKey();
            List<Long> recipientIds = entry.getValue();
            if (recipientIds.isEmpty()) continue;

            String translatedText = translatedContentsMap.get(targetLang);

            ChatMessageResponse messageResponse = new ChatMessageResponse(
                    messageId, roomId, senderId, content, translatedText,
                    sentAt, senderFirstName, senderLastName, userImageUrl, msgType
            );

            // 비동기 전송
            CompletableFuture.runAsync(() ->
                    chatSummaryService.sendSummaryToRecipientsInNewTx(messageResponse, recipientIds)
            );
        }
    }

    @Transactional
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
        // 1. [검증] 실제 스토리지에 파일이 존재하는지 확인 (보안)
        validateObjectStorageFile(req.mediaKey());
        if (req.messageType() == MessageType.VIDEO && req.thumbnailKey() != null) {
            validateObjectStorageFile(req.thumbnailKey()); // 썸네일도 확인
        }

        ChatRoom chatRoom = chatRoomRepository.findById(req.roomId())
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        User sender = userRepository.findById(req.senderId())
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        // 2. 메시지 저장 (파일 Key 저장)
        ChatMessage savedMessage = new ChatMessage(chatRoom, sender, req.mediaKey(), req.messageType());
        chatMessageRepository.save(savedMessage);

        // 3. Image 테이블 저장 (사진 및 동영상 썸네일)
        saveMediaToImageTable(savedMessage, req);

        // 4. 읽음 처리
        chatParticipantRepository.findByChatRoomIdAndUserId(req.roomId(), req.senderId())
                .ifPresent(participant -> participant.setLastReadMessageId(savedMessage.getId()));

        // 5. 수신자 계산 (차단 로직 포함)
        List<Long> recipientIds = chatRoom.getParticipants().stream()
                .map(p -> p.getUser())
                .filter(user -> !blockRepository.existsBlock(user.getId(), sender.getId())
                        && !blockRepository.existsBlock(sender.getId(), user.getId())) // 양방향 체크
                .map(User::getId)
                .toList();

        // 6. 응답 DTO 생성 (Full URL 변환)
        String contentUrl = cdnBaseUrl + "/" + savedMessage.getContent();
        String thumbnailUrl = (req.thumbnailKey() != null) ? cdnBaseUrl + "/" + req.thumbnailKey() : null;

        // 프로필 이미지 조회 등은 성능을 위해 생략하거나 캐시 사용 권장
        String senderProfileUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, sender.getId())
                .map(Image::getUrl).orElse(null);

        ChatMessageResponse messageResponse = new ChatMessageResponse(
                savedMessage.getId(), chatRoom.getId(), sender.getId(),
                contentUrl, // 메인 콘텐츠 (사진/동영상)
                thumbnailUrl, // 썸네일 (동영상일 때만)
                savedMessage.getSentAt(), sender.getFirstName(), sender.getLastName(),
                senderProfileUrl, savedMessage.getMessageType()
        );

        // 7. [비동기 전송] 트랜잭션 커밋 후 이벤트 발행
        ChatRoomSummaryResponse summary = buildChatRoomSummaryResponse(chatRoom.getId(), sender.getId());

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 별도 스레드에서 웹소켓 전송 (트랜잭션 물고 있지 않게 함)
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
    public List<ChatMessageResponse> getMessagesAround(Long roomId, Long userId, Long targetMessageId) {
        // 1. 참여자 조회 (권한 체크)
        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_CHAT_PARTICIPANT));

        // 2. 메시지 조회 (Cursor Paging)
        // 2-1. 과거 메시지
        List<ChatMessage> older = chatMessageRepository.findTop20ByChatRoomIdAndIdLessThanOrderByIdDesc(roomId, targetMessageId);
        Collections.reverse(older);

        // 2-2. 타겟 메시지
        ChatMessage target = chatMessageRepository.findById(targetMessageId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.MESSAGE_NOT_FOUND));

        // 2-3. 미래 메시지
        List<ChatMessage> newer = chatMessageRepository.findTop20ByChatRoomIdAndIdGreaterThanOrderByIdAsc(roomId, targetMessageId);

        // 2-4. 리스트 합치기
        List<ChatMessage> combined = new ArrayList<>();
        combined.addAll(older);
        combined.add(target);
        combined.addAll(newer);

        // 3. [N+1 방지] 보낸 사람 정보 일괄 조회 (Loop 사용)
        Set<Long> senderIds = new HashSet<>();
        for (ChatMessage msg : combined) {
            // [수정] getSenderId() -> getSender().getId() 로 변경
            if (msg.getSender() != null) {
                senderIds.add(msg.getSender().getId());
            }
        }

        List<User> users = userRepository.findAllById(senderIds);
        Map<Long, User> senderMap = new HashMap<>();
        for (User user : users) {
            senderMap.put(user.getId(), user);
        }

        // 4. 번역 처리
        Map<Long, String> translatedMap = Collections.emptyMap();
        if (participant.isTranslateEnabled() && participant.getUser().getTranslateLanguage() != null) {
            translatedMap = chatTranslationService.getTranslatedMessages(combined, participant.getUser().getTranslateLanguage());
        }

        // 5. 응답 변환 (Loop 사용)
        List<ChatMessageResponse> responseList = new ArrayList<>();

        for (ChatMessage msg : combined) {
            // 5-1. 사용자 정보 가져오기 ([수정] getSender().getId() 사용)
            Long currentSenderId = (msg.getSender() != null) ? msg.getSender().getId() : null;
            User sender = senderMap.get(currentSenderId);

            // 5-2. 번역 정보 가져오기
            String translated = translatedMap.get(msg.getId());

            // 5-3. DTO 변환 및 리스트 추가
            ChatMessageResponse response = mapToResponse(msg, sender, translated);
            responseList.add(response);
        }

        return responseList;
    }

    /**
     * [Helper] Entity -> Record DTO 변환
     */
    private ChatMessageResponse mapToResponse(ChatMessage msg, User sender, String translatedContent) {
        String content = msg.getContent();
        String mediaUrl = null;
        String thumbnailUrl = null;

        // --- 미디어(이미지/비디오) 처리 로직 ---
        if (msg.getMessageType() == MessageType.IMAGE || msg.getMessageType() == MessageType.VIDEO) {
            // 1. 원본 URL 생성
            mediaUrl = s3ImageStorageClient.generatePublicUrl(msg.getContent());

            // 2. 비디오라면 썸네일 URL 생성
            if (msg.getMessageType() == MessageType.VIDEO) {
                thumbnailUrl = s3ImageStorageClient.generateThumbnailUrl(msg.getContent());
                content = "동영상"; // 클라이언트 표시용 대체 텍스트
            } else {
                content = "사진";   // 클라이언트 표시용 대체 텍스트
            }
        }

        // --- 보낸 사람 정보 처리 (Null Safety) ---
        String senderFirstName = "Unknown";
        String senderLastName = "";
        String senderImageUrl = null;

        if (sender != null) {
            senderFirstName = sender.getFirstName();
            senderLastName = sender.getLastName();
            senderImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, sender.getId())
                    .map(Image::getUrl).orElse(null);

        }

        // --- ID 추출 수정 ---
        // msg.getSenderId() -> msg.getSender().getId()
        Long msgSenderId = (msg.getSender() != null) ? msg.getSender().getId() : null;

        // msg.getChatRoomId() -> msg.getChatRoom().getId()
        // (만약 엔티티에 getChatRoom()만 있다면 아래처럼 호출해야 함)
        Long msgRoomId = (msg.getChatRoom() != null) ? msg.getChatRoom().getId() : null;

        // --- Record 생성 및 반환 ---
        return new ChatMessageResponse(
                msg.getId(),
                msgRoomId,
                msgSenderId,
                content,            // originContent
                translatedContent,  // targetContent
                msg.getSentAt(),    // sentAt (Instant)
                senderFirstName,
                senderLastName,
                senderImageUrl,
                msg.getMessageType(), // messageType
                mediaUrl,           // mediaUrl
                thumbnailUrl        // thumbnailUrl
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
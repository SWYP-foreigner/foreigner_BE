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
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.enums.ChatParticipantStatus;
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
        // 1. [진단] 스레드 및 트랜잭션 상태 확인
        String currentThreadName = Thread.currentThread().getName();
        boolean isTxActive = TransactionSynchronizationManager.isActualTransactionActive();
        String txName = TransactionSynchronizationManager.getCurrentTransactionName();

        log.info(">>>> [Start Sending] Thread: [{}], TxActive: [{}], TxName: [{}], RoomId: {}",
                currentThreadName, isTxActive, txName, req.roomId());

        StopWatch sw = new StopWatch("ChatSending-" + req.roomId());

        try {
            long absoluteStartTime = System.currentTimeMillis();

            // -------------------------------------------------
            // 구간 1: 메시지 저장 (가장 의심됨)
            // -------------------------------------------------
            sw.start("1. DB Insert (Message)");
            ChatMessage savedMessage = this.saveMessage(req.roomId(), req.senderId(), req.content());
            sw.stop();

            // 저장 직후 데이터 준비
            ChatRoom chatRoom = savedMessage.getChatRoom();
            User sender = savedMessage.getSender();
            String originalContent = savedMessage.getContent();

            // -------------------------------------------------
            // 구간 2: 비동기 작업 스케줄링
            // -------------------------------------------------
            sw.start("2. Async Task Scheduling");
            CompletableFuture.runAsync(() -> checkSpamAndReport(savedMessage));
            sw.stop();

            // -------------------------------------------------
            // 구간 3: 조회 로직 (이미지, 차단 등)
            // -------------------------------------------------
            sw.start("3. DB Select (Meta Data)");
            String userImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, sender.getId())
                    .map(Image::getUrl).orElse(null);

            List<BlockUser> relatedBlocks = blockRepository.findAllRelatedBlocks(sender.getId());
            sw.stop();

            // -------------------------------------------------
            // 구간 4: 로직 처리 (그룹핑)
            // -------------------------------------------------
            sw.start("4. Java Logic (Grouping)");
            Set<Long> blockedUserIds = relatedBlocks.stream()
                    .map(b -> b.getUser().getId().equals(sender.getId()) ? b.getBlocked().getId() : b.getUser().getId())
                    .collect(Collectors.toSet());

            Map<String, List<Long>> recipientsByLang = new HashMap<>();

            for (ChatParticipant p : chatRoom.getParticipants()) {
                User recipient = p.getUser();
                if (blockedUserIds.contains(recipient.getId())) continue;

                if (recipient.getId().equals(sender.getId())) {
                    p.setLastReadMessageId(savedMessage.getId());
                    recipientsByLang.computeIfAbsent("SELF", k -> new ArrayList<>()).add(recipient.getId());
                    continue;
                }

                String lang = (p.isTranslateEnabled() && p.getUser().getTranslateLanguage() != null)
                        ? p.getUser().getTranslateLanguage()
                        : "NONE";

                recipientsByLang.computeIfAbsent(lang, k -> new ArrayList<>()).add(recipient.getId());
            }
            sw.stop();

            // -------------------------------------------------
            // 구간 5: 번역 API 및 이벤트 발행
            // -------------------------------------------------
            sw.start("5. Translation & Publish");

            for (Map.Entry<String, List<Long>> entry : recipientsByLang.entrySet()) {
                String targetLang = entry.getKey();
                List<Long> recipientIds = entry.getValue();
                if (recipientIds.isEmpty()) continue;

                String translatedContent = null;

                if ("SELF".equals(targetLang)) {
                    translatedContent = originalContent;
                } else if (!"NONE".equals(targetLang)) {
                    // 실제 번역 API 호출 시점 체크
                    long tStart = System.currentTimeMillis();
                    try {
                        List<String> results = translationService.translateMessages(List.of(originalContent), targetLang);
                        if (!results.isEmpty()) translatedContent = results.get(0);
                    } catch (Exception e) {
                        log.error("Translation Error", e);
                    }
                    long tEnd = System.currentTimeMillis();
                    if ((tEnd - tStart) > 200) {
                        log.warn("!!!! [Slow API] Translation took {} ms for {}", (tEnd - tStart), targetLang);
                    }
                }

                ChatMessageResponse messageResponse = new ChatMessageResponse(
                        savedMessage.getId(), chatRoom.getId(), sender.getId(),
                        originalContent, translatedContent, savedMessage.getSentAt(),
                        sender.getFirstName(), sender.getLastName(), userImageUrl, MessageType.TEXT
                );

                ChatRoomSummaryResponse summary = buildChatRoomSummaryResponse(chatRoom.getId(), sender.getId());

                // 이벤트 발행
                eventPublisher.publishEvent(new MessageSentEvent(messageResponse, recipientIds, summary, absoluteStartTime));
            }
            sw.stop();

            // -------------------------------------------------
            // 최종 로그 출력
            // -------------------------------------------------
            log.info(">>>> [Process Complete] Thread: {}", Thread.currentThread().getName());
            log.info(sw.prettyPrint());

        } catch (Exception e) {
            log.error("Error in processAndSendChatMessage", e);
            throw e;
        }
    }

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
            eventPublisher.publishEvent(new MessageSentEvent(messageResponse, recipientIds, summary,absoluteStartTime));

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
        return messages.stream()
                .map(message -> ChatMessageFirstResponse.fromEntity(message, chatRoom, finalMap.get(message.getSender().getId())))
                .collect(Collectors.toList());
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

    private ChatRoomSummaryResponse buildChatRoomSummaryResponse(Long roomId, Long forUserId) {
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
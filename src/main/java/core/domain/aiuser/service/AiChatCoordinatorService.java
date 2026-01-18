package core.domain.aiuser.service;

import core.domain.aiuser.dto.MessageCreatedEvent;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.user.entity.User;
import core.global.enums.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AiChatCoordinatorService {

    private final AiChatUserService aiChatUserService;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ThreadPoolTaskScheduler taskScheduler;
    private final AiThinkingStateManager thinkingStateManager;

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final int ACTIVE_TALKER_WINDOW_MINUTES = 5;
    private static final int MAX_FAST_REPLIES_PER_TURN = 2;

    public void coordinateReplies(Long roomId, Set<User> aiParticipants, MessageCreatedEvent lastEvent, String userMessage) {

        boolean isGroupChat = chatRoomRepository.isGroupChat(roomId);

        if (isGroupChat && shouldSkipByProbabilityDecay(roomId)) {
            log.info("💤 AI Chat Faded out (Probability Decay triggered) | RoomId: {}", roomId);
            return;
        }

        List<User> aiList = new ArrayList<>(aiParticipants);
        Collections.shuffle(aiList);

        // 1. DB상 마지막 화자 (과거)
        Long lastAiSpeakerId = findLastAiSpeakerId(roomId);

        // 2. 우선순위 정렬 (DB 기록 + 🟢 현재 생각 중인 AI 포함)
        sortParticipantsByPriority(aiList, roomId, userMessage, lastAiSpeakerId);

        if (aiList.isEmpty()) return;

        long accumulatedFastDelay = 0;
        int currentFastCount = 0;
        long baseThinkingTime = calculateBaseThinkingTime(userMessage);

        for (int i = 0; i < aiList.size(); i++) {
            User aiUser = aiList.get(i);
            boolean isMainSpeakerCandidate = (i == 0);

            // 1. 기본 타입 결정
            ResponseType responseType = determineResponseType(aiUser, roomId, userMessage, isMainSpeakerCandidate);

            // 2. Quota(쿼터) 체크
            boolean isDirectlyMentioned = isMentioned(userMessage, aiUser.getFirstName());
            boolean isLastSpeaker = (lastAiSpeakerId != null && aiUser.getId().equals(lastAiSpeakerId));

            // 🟢 [NEW] 현재 생각 중인 AI도 Last Speaker와 동급으로 대우 (쿼터 면제/우선권)
            boolean isThinkingNow = thinkingStateManager.isThinking(roomId, aiUser.getId());

            if (responseType == ResponseType.FAST) {
                // 멘션도 아니고, 마지막 화자도 아니고, 지금 말하고 있는 애도 아니면 -> 쿼터 적용
                if (!isDirectlyMentioned && !isLastSpeaker && !isThinkingNow) {
                    if (currentFastCount >= MAX_FAST_REPLIES_PER_TURN) {
                        responseType = ResponseType.SLOW;
                    } else {
                        currentFastCount++;
                    }
                } else {
                    currentFastCount++;
                }
            }

            if (responseType == ResponseType.FAST) {
                long randomGap = secureRandom.nextLong(1000, 3000);
                long totalStepDelay = baseThinkingTime + randomGap;
                accumulatedFastDelay += totalStepDelay;

                scheduleFastResponse(aiUser, lastEvent, userMessage, isMainSpeakerCandidate, accumulatedFastDelay);

            } else {
                long lateDelayMinutes = secureRandom.nextLong(10, 60);
                scheduleLateReply(aiUser, lastEvent, userMessage, roomId, lateDelayMinutes);
            }
        }
    }

    private Long findLastAiSpeakerId(Long roomId) {
        List<ChatMessage> history = chatMessageRepository.findTop10ByChatRoomIdOrderBySentAtDesc(roomId);
        for (ChatMessage msg : history) {
            if (msg.getSender().getUserRole() == Role.AI) {
                return msg.getSender().getId();
            }
        }
        return null;
    }

    // 정렬 로직 (메모리 상태 'Thinking' 확인 추가)
    private void sortParticipantsByPriority(List<User> aiList, Long roomId, String userMessage, Long lastAiSpeakerId) {
        Instant fiveMinutesAgo = Instant.now().minus(Duration.ofMinutes(ACTIVE_TALKER_WINDOW_MINUTES));
        Set<Long> activeTalkerIds = new HashSet<>();
        for (User ai : aiList) {
            if (chatMessageRepository.existsBySenderIdAndChatRoomIdAndSentAtAfter(ai.getId(), roomId, fiveMinutesAgo)) {
                activeTalkerIds.add(ai.getId());
            }
        }

        aiList.sort((u1, u2) -> {
            // 1순위: 이름 멘션
            boolean u1Mentioned = isMentioned(userMessage, u1.getFirstName());
            boolean u2Mentioned = isMentioned(userMessage, u2.getFirstName());
            if (u1Mentioned && !u2Mentioned) return -1;
            if (!u1Mentioned && u2Mentioned) return 1;

            // 🟢 2순위: 현재 생각 중(Processing)이거나 직전 화자(Last Speaker)
            // (DB에 아직 안 들어갔어도, 지금 답변 생성 중이면 우선권을 줌)
            boolean u1Target = (lastAiSpeakerId != null && u1.getId().equals(lastAiSpeakerId))
                    || thinkingStateManager.isThinking(roomId, u1.getId());
            boolean u2Target = (lastAiSpeakerId != null && u2.getId().equals(lastAiSpeakerId))
                    || thinkingStateManager.isThinking(roomId, u2.getId());

            if (u1Target && !u2Target) return -1;
            if (!u1Target && u2Target) return 1;

            // 3순위: 최근 활동 (Active Talker)
            boolean u1Active = activeTalkerIds.contains(u1.getId());
            boolean u2Active = activeTalkerIds.contains(u2.getId());
            if (u1Active && !u2Active) return -1;
            if (!u1Active && u2Active) return 1;

            return 0;
        });
    }

    private ResponseType determineResponseType(User aiUser, Long roomId, String userMessage, boolean isMainSpeaker) {
        if (thinkingStateManager.isThinking(roomId, aiUser.getId())) return ResponseType.FAST;

        if (isMentioned(userMessage, aiUser.getFirstName())) return ResponseType.FAST;

        Instant fiveMinutesAgo = Instant.now().minus(Duration.ofMinutes(ACTIVE_TALKER_WINDOW_MINUTES));
        boolean isActiveTalker = chatMessageRepository.existsBySenderIdAndChatRoomIdAndSentAtAfter(aiUser.getId(), roomId, fiveMinutesAgo);

        if (isActiveTalker) return ResponseType.FAST;
        if (isMainSpeaker) return ResponseType.FAST;

        return (secureRandom.nextInt(100) < 30) ? ResponseType.FAST : ResponseType.SLOW;
    }

    //  스케줄링 시 상태 마킹(Start) 및 해제(End) 추가
    private void scheduleFastResponse(User aiUser, MessageCreatedEvent event, String userMessage, boolean isMainSpeaker, long delayMs) {
        Instant executionTime = Instant.now().plusMillis(delayMs);
        Long roomId = event.messageResponse().roomId();

        // 1. 스케줄링 등록 즉시 마킹 (순서 보장)
        thinkingStateManager.markAsThinking(roomId, aiUser.getId());

        taskScheduler.schedule(() -> {
            try {
                aiChatUserService.processAiResponse(aiUser, event, userMessage, isMainSpeaker);
            } catch (Exception e) {
                log.error("Fast Response Error", e);
            } finally {
                // 2. 작업이 끝나면(성공이든 실패든) 무조건 해제
                thinkingStateManager.finishThinking(roomId, aiUser.getId());
            }
        }, executionTime);
    }

    private void scheduleLateReply(User aiUser, MessageCreatedEvent originalEvent, String originalUserMessage, Long roomId, long delayMinutes) {
        Instant executionTime = Instant.now().plus(Duration.ofMinutes(delayMinutes));
        log.info("🕒 AI [{}] scheduled LATE reply in {} minutes.", aiUser.getFirstName(), delayMinutes);
        taskScheduler.schedule(() -> {
            validateAndSendLateReply(aiUser, originalEvent, roomId);
        }, executionTime);
    }

    @Transactional
    public void validateAndSendLateReply(User aiUser, MessageCreatedEvent originalEvent, Long roomId) {
        try {
            Optional<ChatMessage> latestMsgOpt = chatMessageRepository.findTopByChatRoomIdOrderBySentAtDesc(roomId);
            if (latestMsgOpt.isEmpty()) return;
            ChatMessage latestMsg = latestMsgOpt.get();

            if (latestMsg.getId() > originalEvent.messageResponse().id()) {
                log.info("✋ AI [{}] Late reply ABORTED. Context changed.", aiUser.getFirstName());
                return;
            }
            aiChatUserService.processAiResponse(aiUser, originalEvent, latestMsg.getContent(), false);
        } catch (Exception e) {
            log.error("Late Response Error", e);
        }
    }

    private long calculateBaseThinkingTime(String userMessage) {
        long baseReactionTime = secureRandom.nextLong(500, 1500);
        long readingTime = (userMessage != null ? userMessage.length() : 0) * 50L;
        long thinkingVariance = secureRandom.nextLong(0, 1000);

        return baseReactionTime + readingTime + thinkingVariance;
    }

    private boolean shouldSkipByProbabilityDecay(Long roomId) {
        List<ChatMessage> history = chatMessageRepository.findTop5ByChatRoomIdOrderBySentAtDesc(roomId);
        int aiStreak = 0;
        for (ChatMessage msg : history) {
            if (msg.getSender().getUserRole() == Role.AI) {
                aiStreak++;
            } else {
                break;
            }
        }
        int survivalProb;
        switch (aiStreak) {
            case 0: survivalProb = 100; break;
            case 1: survivalProb = 80;  break;
            case 2: survivalProb = 50;  break;
            case 3: survivalProb = 10;  break;
            default: survivalProb = 0;  break;
        }
        int roll = secureRandom.nextInt(100);
        return roll >= survivalProb;
    }

    private boolean isMentioned(String message, String name) {
        if (message == null || name == null) return false;
        return message.contains(name);
    }

    private enum ResponseType { FAST, SLOW }
}
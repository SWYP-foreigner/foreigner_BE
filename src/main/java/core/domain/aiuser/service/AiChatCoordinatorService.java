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

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final int ACTIVE_TALKER_WINDOW_MINUTES = 5;

    // 한 턴에 즉시 반응(Fast Group)할 수 있는 최대 AI 수 제한 (Quota)
    private static final int MAX_FAST_REPLIES_PER_TURN = 2;

    public void coordinateReplies(Long roomId, Set<User> aiParticipants, MessageCreatedEvent lastEvent, String userMessage) {

        // 1. [Group Only] 확률 감쇠(Soft Cap) 로직
        boolean isGroupChat = chatRoomRepository.isGroupChat(roomId);

        if (isGroupChat && shouldSkipByProbabilityDecay(roomId)) {
            log.info("💤 AI Chat Faded out (Probability Decay triggered) | RoomId: {}", roomId);
            return;
        }

        List<User> aiList = new ArrayList<>(aiParticipants);
        Collections.shuffle(aiList);

        // 2. 우선순위 정렬
        sortParticipantsByPriority(aiList, roomId, userMessage);

        if (aiList.isEmpty()) return;

        // 🔴 [수정] 누적 딜레이 계산 시작 (초기값 0)
        long accumulatedFastDelay = 0;
        int currentFastCount = 0;

        // 🔴 [추가] 메시지 길이에 따른 기본 읽기/생각 시간 계산 (공통)
        long baseThinkingTime = calculateBaseThinkingTime(userMessage);

        for (int i = 0; i < aiList.size(); i++) {
            User aiUser = aiList.get(i);
            boolean isMainSpeakerCandidate = (i == 0);

            // 1. 기본 타입 결정 (Fast/Slow)
            ResponseType responseType = determineResponseType(aiUser, roomId, userMessage, isMainSpeakerCandidate);

            // 2. Quota(쿼터) 체크
            boolean isDirectlyMentioned = isMentioned(userMessage, aiUser.getFirstName());

            if (responseType == ResponseType.FAST && !isDirectlyMentioned) {
                if (currentFastCount >= MAX_FAST_REPLIES_PER_TURN) {
                    responseType = ResponseType.SLOW;
                } else {
                    currentFastCount++;
                }
            }

            if (responseType == ResponseType.FAST) {
                // 🔴 [수정] Blocking Sleep 제거를 위한 딜레이 선반영 로직
                // - 기존: 2~5초 랜덤
                // - 변경: (기본 읽는 시간) + (1~3초 랜덤 간격)
                // - 효과: 메시지가 길면 더 오래 기다렸다가 답장함 (자연스러움)
                long randomGap = secureRandom.nextLong(1000, 3000);
                long totalStepDelay = baseThinkingTime + randomGap;

                accumulatedFastDelay += totalStepDelay; // 앞사람 시간만큼 누적하여 순차 발송 보장

                scheduleFastResponse(aiUser, lastEvent, userMessage, isMainSpeakerCandidate, accumulatedFastDelay);

            } else {
                long lateDelayMinutes = secureRandom.nextLong(10, 60);
                scheduleLateReply(aiUser, lastEvent, userMessage, roomId, lateDelayMinutes);
            }
        }
    }

    // 🔴 [이동] UserService에 있던 시간 계산 로직을 여기로 가져옴
    private long calculateBaseThinkingTime(String userMessage) {
        long baseDelay = 500; // 기본 0.5초
        long typingDelay = (userMessage != null ? userMessage.length() : 0) * 50L; // 글자당 0.05초
        return baseDelay + typingDelay;
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
        boolean survive = roll < survivalProb;

        log.info("🎲 Decay Check | Streak: {} | Prob: {}% | Roll: {} | Result: {}",
                aiStreak, survivalProb, roll, survive ? "Survive" : "Die");

        return !survive;
    }

    private void sortParticipantsByPriority(List<User> aiList, Long roomId, String userMessage) {
        Instant fiveMinutesAgo = Instant.now().minus(Duration.ofMinutes(ACTIVE_TALKER_WINDOW_MINUTES));
        Set<Long> activeTalkerIds = new HashSet<>();
        for (User ai : aiList) {
            if (chatMessageRepository.existsBySenderIdAndChatRoomIdAndSentAtAfter(ai.getId(), roomId, fiveMinutesAgo)) {
                activeTalkerIds.add(ai.getId());
            }
        }
        aiList.sort((u1, u2) -> {
            boolean u1Mentioned = isMentioned(userMessage, u1.getFirstName());
            boolean u2Mentioned = isMentioned(userMessage, u2.getFirstName());
            if (u1Mentioned && !u2Mentioned) return -1;
            if (!u1Mentioned && u2Mentioned) return 1;
            boolean u1Active = activeTalkerIds.contains(u1.getId());
            boolean u2Active = activeTalkerIds.contains(u2.getId());
            if (u1Active && !u2Active) return -1;
            if (!u1Active && u2Active) return 1;
            return 0;
        });
    }

    private ResponseType determineResponseType(User aiUser, Long roomId, String userMessage, boolean isMainSpeaker) {
        if (isMentioned(userMessage, aiUser.getFirstName())) return ResponseType.FAST;
        Instant fiveMinutesAgo = Instant.now().minus(Duration.ofMinutes(ACTIVE_TALKER_WINDOW_MINUTES));
        boolean isActiveTalker = chatMessageRepository.existsBySenderIdAndChatRoomIdAndSentAtAfter(aiUser.getId(), roomId, fiveMinutesAgo);
        if (isActiveTalker) return ResponseType.FAST;
        if (isMainSpeaker) return ResponseType.FAST;
        return (secureRandom.nextInt(100) < 30) ? ResponseType.FAST : ResponseType.SLOW;
    }

    private void scheduleFastResponse(User aiUser, MessageCreatedEvent event, String userMessage, boolean isMainSpeaker, long delayMs) {
        Instant executionTime = Instant.now().plusMillis(delayMs);
        taskScheduler.schedule(() -> {
            try {
                aiChatUserService.processAiResponse(aiUser, event, userMessage, isMainSpeaker);
            } catch (Exception e) {
                log.error("Fast Response Error", e);
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

    private boolean isMentioned(String message, String name) {
        if (message == null || name == null) return false;
        return message.contains(name);
    }

    private enum ResponseType { FAST, SLOW }
}

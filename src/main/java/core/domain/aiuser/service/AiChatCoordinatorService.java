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

    public void coordinateReplies(Long roomId, Set<User> aiParticipants, MessageCreatedEvent lastEvent, String userMessage) {

        // 1. [Group Only] 확률 감쇠(Soft Cap) 로직 적용
        // 1:1 채팅이 아니고, 확률 컷오프에 걸리면 이번 턴은 아무도 대답하지 않고 종료합니다.
        boolean isGroupChat = chatRoomRepository.isGroupChat(roomId);

        if (isGroupChat && shouldSkipByProbabilityDecay(roomId)) {
            log.info("💤 AI Chat Faded out (Probability Decay triggered) | RoomId: {}", roomId);
            return; // 이번 이벤트는 무시 (대화 종료)
        }

        List<User> aiList = new ArrayList<>(aiParticipants);
        Collections.shuffle(aiList);

        // 2. 우선순위 정렬 (Active Talker 보장)
        sortParticipantsByPriority(aiList, roomId, userMessage);

        if (aiList.isEmpty()) return;

        long accumulatedFastDelay = 0;

        for (int i = 0; i < aiList.size(); i++) {
            User aiUser = aiList.get(i);
            boolean isMainSpeakerCandidate = (i == 0);

            ResponseType responseType = determineResponseType(aiUser, roomId, userMessage, isMainSpeakerCandidate);

            if (responseType == ResponseType.FAST) {
                long myDelay = secureRandom.nextLong(2000, 5000);
                accumulatedFastDelay += myDelay;
                scheduleFastResponse(aiUser, lastEvent, userMessage, isMainSpeakerCandidate, accumulatedFastDelay);

            } else {
                long lateDelayMinutes = secureRandom.nextLong(10, 60);
                scheduleLateReply(aiUser, lastEvent, userMessage, roomId, lateDelayMinutes);
            }
        }
    }

    /**
     * 🔥 [신규] 확률 감쇠 로직 (Probability Decay)
     * AI끼리의 연속 대화(Streak)가 길어질수록, 다음 대화가 발생할 확률을 낮춥니다.
     * @return true면 스킵(대화 종료), false면 진행
     */
    private boolean shouldSkipByProbabilityDecay(Long roomId) {
        // 1. 최근 메시지 조회
        List<ChatMessage> history = chatMessageRepository.findTop5ByChatRoomIdOrderBySentAtDesc(roomId);

        // 2. AI 연속 발언 횟수(Streak) 계산
        int aiStreak = 0;
        for (ChatMessage msg : history) {
            if (msg.getSender().getUserRole() == Role.AI) { // Role.AI 확인 필요
                aiStreak++;
            } else {
                // 사람이 말한 순간 Streak 끊김
                break;
            }
        }

        // 3. Streak에 따른 생존 확률 결정
        int survivalProb;
        switch (aiStreak) {
            case 0: survivalProb = 100; break; // 사람이 방금 말함 -> 무조건 반응
            case 1: survivalProb = 80;  break; // AI 1명 대답함 -> 80% 확률로 티키타카
            case 2: survivalProb = 50;  break; // AI 2명 대답함 -> 50% 확률로 연장
            case 3: survivalProb = 10;  break; // AI 3명 대답함 -> 10% (거의 끝)
            default: survivalProb = 0;  break; // 4절 이상 금지 (강제 종료)
        }

        // 4. 주사위 굴리기
        int roll = secureRandom.nextInt(100);
        boolean survive = roll < survivalProb;

        log.info("🎲 Decay Check | Streak: {} | Prob: {}% | Roll: {} | Result: {}",
                aiStreak, survivalProb, roll, survive ? "Survive" : "Die");

        return !survive; // 생존 실패(false)하면 skip(true) 반환
    }

    // --- 기존 로직 유지 ---

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
        boolean isActiveTalker = chatMessageRepository.existsBySenderIdAndChatRoomIdAndSentAtAfter(
                aiUser.getId(), roomId, fiveMinutesAgo
        );

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

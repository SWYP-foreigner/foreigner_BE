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

        // 🟢 [추가] 직전에 말한 AI가 누구인지 찾기 (Last Speaker Priority)
        Long lastAiSpeakerId = findLastAiSpeakerId(roomId);

        // 2. 우선순위 정렬 (Last Speaker ID 전달)
        sortParticipantsByPriority(aiList, roomId, userMessage, lastAiSpeakerId);

        if (aiList.isEmpty()) return;

        // 누적 딜레이 계산 시작 (초기값 0)
        long accumulatedFastDelay = 0;
        int currentFastCount = 0;

        // 메시지 길이에 따른 기본 읽기/생각 시간 계산 (공통)
        long baseThinkingTime = calculateBaseThinkingTime(userMessage);

        for (int i = 0; i < aiList.size(); i++) {
            User aiUser = aiList.get(i);
            boolean isMainSpeakerCandidate = (i == 0);

            // 1. 기본 타입 결정 (Fast/Slow)
            ResponseType responseType = determineResponseType(aiUser, roomId, userMessage, isMainSpeakerCandidate);

            // 2. Quota(쿼터) 체크
            // 멘션되었거나, 방금 말한 사람(티키타카 중)이면 쿼터 무시하고 진행할 수도 있지만,
            // 여기서는 일단 Fast 그룹에 우선 배정하는 것으로 처리
            boolean isDirectlyMentioned = isMentioned(userMessage, aiUser.getFirstName());
            boolean isLastSpeaker = (lastAiSpeakerId != null && aiUser.getId().equals(lastAiSpeakerId));

            if (responseType == ResponseType.FAST) {
                // 멘션도 아니고 방금 말한 사람도 아닌데(그냥 Active라서 Fast된 경우), 쿼터가 찼으면 Slow로 강등
                if (!isDirectlyMentioned && !isLastSpeaker) {
                    if (currentFastCount >= MAX_FAST_REPLIES_PER_TURN) {
                        responseType = ResponseType.SLOW;
                    } else {
                        currentFastCount++;
                    }
                } else {
                    // 멘션 or LastSpeaker는 쿼터 카운트는 올리되, 강등되진 않음 (우선권)
                    currentFastCount++;
                }
            }

            if (responseType == ResponseType.FAST) {
                // Blocking Sleep 제거를 위한 딜레이 선반영 로직
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

    // 🟢 [신규 메서드] 가장 최근에 말한 AI의 ID 조회
    // (성능: 인덱스 타는 쿼리라 매우 빠름)
    private Long findLastAiSpeakerId(Long roomId) {
        // JPA 메서드 필요: List<ChatMessage> findTop10ByChatRoomIdOrderBySentAtDesc(Long chatRoomId);
        List<ChatMessage> history = chatMessageRepository.findTop10ByChatRoomIdOrderBySentAtDesc(roomId);

        for (ChatMessage msg : history) {
            // Role 체크 (Enum 이름은 프로젝트에 맞게 확인 필요: Role.AI 등)
            if (msg.getSender().getUserRole() == Role.AI) {
                return msg.getSender().getId();
            }
        }
        return null;
    }

    // 🔴 [수정됨] 정렬 로직 (Last Speaker 우선순위 추가)
    private void sortParticipantsByPriority(List<User> aiList, Long roomId, String userMessage, Long lastAiSpeakerId) {
        Instant fiveMinutesAgo = Instant.now().minus(Duration.ofMinutes(ACTIVE_TALKER_WINDOW_MINUTES));
        Set<Long> activeTalkerIds = new HashSet<>();

        for (User ai : aiList) {
            if (chatMessageRepository.existsBySenderIdAndChatRoomIdAndSentAtAfter(ai.getId(), roomId, fiveMinutesAgo)) {
                activeTalkerIds.add(ai.getId());
            }
        }

        aiList.sort((u1, u2) -> {
            // 1순위: 이름 멘션 여부
            boolean u1Mentioned = isMentioned(userMessage, u1.getFirstName());
            boolean u2Mentioned = isMentioned(userMessage, u2.getFirstName());
            if (u1Mentioned && !u2Mentioned) return -1;
            if (!u1Mentioned && u2Mentioned) return 1;

            // 2순위: 직전 대화자 여부 (Last Speaker) - [티키타카 보호 핵심]
            boolean u1IsLast = (lastAiSpeakerId != null && u1.getId().equals(lastAiSpeakerId));
            boolean u2IsLast = (lastAiSpeakerId != null && u2.getId().equals(lastAiSpeakerId));
            if (u1IsLast && !u2IsLast) return -1;
            if (!u1IsLast && u2IsLast) return 1;

            // 3순위: 최근 활동 여부 (Active Talker)
            boolean u1Active = activeTalkerIds.contains(u1.getId());
            boolean u2Active = activeTalkerIds.contains(u2.getId());
            if (u1Active && !u2Active) return -1;
            if (!u1Active && u2Active) return 1;

            return 0;
        });
    }

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
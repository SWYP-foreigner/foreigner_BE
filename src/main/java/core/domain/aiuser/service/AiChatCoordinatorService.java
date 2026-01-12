package core.domain.aiuser.service;

import core.domain.aiuser.dto.MessageCreatedEvent;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.user.entity.User;
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
public class AiChatCoordinatorService {

    private final AiChatUserService aiChatUserService;

    // [추가 1] 관성(Momentum) 파악을 위한 레포지토리
    private final ChatMessageRepository chatMessageRepository;

    // [추가 2] Thread.sleep 대신 사용할 비동기 스케줄러
    private final ThreadPoolTaskScheduler taskScheduler;

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final int ACTIVE_TALKER_WINDOW_MINUTES = 5; // 관성 유지 시간 (5분)

    /**
     * 여러 AI의 응답 순서와 타이밍을 조율하는 메인 메서드
     */
    public void coordinateReplies(Long roomId, Set<User> aiParticipants, MessageCreatedEvent lastEvent, String userMessage) {

        List<User> aiList = new ArrayList<>(aiParticipants);

        // 1. 일단 섞습니다 (Active가 아닌 나머지 애들의 순서를 랜덤으로 하기 위해)
        Collections.shuffle(aiList);

        // 2. [핵심 수정] 우선순위 정렬 (멘션된 사람 > 방금 말한 사람 > 나머지)
        // 이 정렬을 통해 Active Talker가 무조건 리스트 0번(Main Speaker)을 차지하게 만듭니다.
        // 이렇게 해야 'UserService'에서 피로도 면제권(Main Speaker 권한)을 얻어 대화가 끊기지 않습니다.
        sortParticipantsByPriority(aiList, roomId, userMessage);

        if (aiList.isEmpty()) return;

        // [추가 3] Fast Group 간의 겹침 방지를 위한 누적 딜레이 변수
        long accumulatedFastDelay = 0;

        for (int i = 0; i < aiList.size(); i++) {
            User aiUser = aiList.get(i);

            // 정렬 덕분에 Active Talker는 무조건 i=0이 되어 Main Speaker 자격을 얻음
            boolean isMainSpeakerCandidate = (i == 0);

            // [핵심 변경] 실행 전에 미리 타입을 결정합니다.
            // 기존에는 UserService가 알아서 했지만, 이제는 Coordinator가 스케줄링을 해야 하므로 여기서 판단합니다.
            ResponseType responseType = determineResponseType(aiUser, roomId, userMessage, isMainSpeakerCandidate);

            if (responseType == ResponseType.FAST) {
                // [Fast Group] 티키타카 (2~5초 간격 순차 실행)
                long myDelay = secureRandom.nextLong(2000, 5000);
                accumulatedFastDelay += myDelay;

                // 즉시 실행이 아니라 '빠른 미래'에 예약
                scheduleFastResponse(aiUser, lastEvent, userMessage, isMainSpeakerCandidate, accumulatedFastDelay);

            } else {
                // [Slow Group] 시간차 공격 (10분 ~ 60분 뒤 실행)
                // 유저가 AI를 잊을 때쯤 말을 걸기 위함
                long lateDelayMinutes = secureRandom.nextLong(10, 60);
                scheduleLateReply(aiUser, lastEvent, userMessage, roomId, lateDelayMinutes);
            }
        }
    }

    /**
     * [Helper] 참여자 우선순위 정렬
     * 1순위: 멘션됨 (호출 시 최우선)
     * 2순위: Active Talker (대화 관성 유지)
     * 3순위: 나머지 (이미 셔플된 상태)
     */
    private void sortParticipantsByPriority(List<User> aiList, Long roomId, String userMessage) {
        // Active Talker 정보를 미리 조회하여 Map에 담아둡니다 (반복적인 DB 조회 방지용)
        Instant fiveMinutesAgo = Instant.now().minus(Duration.ofMinutes(ACTIVE_TALKER_WINDOW_MINUTES));
        Set<Long> activeTalkerIds = new HashSet<>();

        for (User ai : aiList) {
            if (chatMessageRepository.existsBySenderIdAndChatRoomIdAndSentAtAfter(ai.getId(), roomId, fiveMinutesAgo)) {
                activeTalkerIds.add(ai.getId());
            }
        }

        aiList.sort((u1, u2) -> {
            // 1. 멘션 여부 체크
            boolean u1Mentioned = isMentioned(userMessage, u1.getFirstName());
            boolean u2Mentioned = isMentioned(userMessage, u2.getFirstName());

            if (u1Mentioned && !u2Mentioned) return -1; // u1 우선
            if (!u1Mentioned && u2Mentioned) return 1;  // u2 우선

            // 2. Active Talker 체크
            boolean u1Active = activeTalkerIds.contains(u1.getId());
            boolean u2Active = activeTalkerIds.contains(u2.getId());

            if (u1Active && !u2Active) return -1; // u1 우선
            if (!u1Active && u2Active) return 1;  // u2 우선

            return 0; // 순서 유지 (이미 셔플되었으므로 랜덤)
        });
    }

    /**
     * [신규 로직] AI의 현재 상태와 문맥을 보고 반응 속도(Fast/Slow)를 결정
     * 이 로직이 원래 UserService에 있던 판단 로직을 흡수하고 확장한 것입니다.
     */
    private ResponseType determineResponseType(User aiUser, Long roomId, String userMessage, boolean isMainSpeaker) {

        // 1. 직접 호출(Mention) 되었는가? -> 무조건 Fast
        // (Coordinator가 이걸 알아야 즉시 반응 스케줄을 잡음)
        if (isMentioned(userMessage, aiUser.getFirstName())) {
            return ResponseType.FAST;
        }

        // 2. [관성 로직] 최근 5분 내에 말을 했는가? -> 무조건 Fast
        // 이유: 방금 말하던 애가 갑자기 1시간 뒤에 답장하면 이상하니까.
        Instant fiveMinutesAgo = Instant.now().minus(Duration.ofMinutes(ACTIVE_TALKER_WINDOW_MINUTES));
        boolean isActiveTalker = chatMessageRepository.existsBySenderIdAndChatRoomIdAndSentAtAfter(
                aiUser.getId(), roomId, fiveMinutesAgo
        );

        if (isActiveTalker) {
            return ResponseType.FAST;
        }

        // 3. 메인 스피커(1번 타자)인가? -> Fast
        // 누군가는 빨리 대답해줘야 대화가 시작됨.
        if (isMainSpeaker) {
            return ResponseType.FAST;
        }

        // 4. 그 외(Lurker) -> 랜덤 배정 (30% 확률로 끼어들기, 70% 확률로 나중에 보기)
        return (secureRandom.nextInt(100) < 30) ? ResponseType.FAST : ResponseType.SLOW;
    }

    /**
     * [Fast Group] 스케줄링 (2~10초 내 실행)
     */
    private void scheduleFastResponse(User aiUser, MessageCreatedEvent event, String userMessage, boolean isMainSpeaker, long delayMs) {
        Instant executionTime = Instant.now().plusMillis(delayMs);

        taskScheduler.schedule(() -> {
            try {
                // 실제 실행은 UserService에게 위임
                aiChatUserService.processAiResponse(aiUser, event, userMessage, isMainSpeaker);
            } catch (Exception e) {
                log.error("Fast Response Error", e);
            }
        }, executionTime);
    }

    /**
     * [Slow Group] 스케줄링 (10~60분 뒤 실행)
     */
    private void scheduleLateReply(User aiUser, MessageCreatedEvent originalEvent, String originalUserMessage, Long roomId, long delayMinutes) {
        Instant executionTime = Instant.now().plus(Duration.ofMinutes(delayMinutes));

        log.info("🕒 AI [{}] scheduled LATE reply in {} minutes.", aiUser.getFirstName(), delayMinutes);

        taskScheduler.schedule(() -> {
            // 시간이 많이 지났으므로, 실행 직전에 상황이 변했는지 체크하는 로직 호출
            validateAndSendLateReply(aiUser, originalEvent, roomId);
        }, executionTime);
    }

    /**
     * [뒷북 방지] 지연 실행 시점에 대화 맥락이 유효한지 검증
     * 트랜잭션이 필요하므로 별도 메서드로 분리
     */
    @Transactional
    public void validateAndSendLateReply(User aiUser, MessageCreatedEvent originalEvent, Long roomId) {
        try {
            // 1. 현재 방의 가장 최신 메시지 조회
            Optional<ChatMessage> latestMsgOpt = chatMessageRepository.findTopByChatRoomIdOrderBySentAtDesc(roomId);

            if (latestMsgOpt.isEmpty()) return;
            ChatMessage latestMsg = latestMsgOpt.get();

            // 2. [검증] 내가 반응하려던 메시지 ID보다 더 최신 메시지가 있는가?
            // (즉, 그 사이에 유저나 다른 AI가 떠들었는가?)
            if (latestMsg.getId() > originalEvent.messageResponse().id()) {
                // 상황이 변했으므로 전송 포기 (뒷북 방지)
                log.info("✋ AI [{}] Late reply ABORTED. Context changed.", aiUser.getFirstName());
                return;
            }

            // 3. 아직 아무도 말을 안 했다면(정적 상태) -> 지금이라도 대답!
            // isMainSpeaker = false (끼어들기 모드)
            // 내용은 최신 메시지(latestMsg.getContent())를 기반으로 생성
            aiChatUserService.processAiResponse(aiUser, originalEvent, latestMsg.getContent(), false);

        } catch (Exception e) {
            log.error("Late Response Error", e);
        }
    }

    // [추가 4] isMentioned 로직을 Coordinator로 가져옴 (판단용)
    private boolean isMentioned(String message, String name) {
        if (message == null || name == null) return false;
        return message.contains(name);
        // (필요하다면 UserService에 있는 퍼지 매칭 로직을 복사해오거나, 공통 유틸로 분리하세요)
    }

    private enum ResponseType {
        FAST, SLOW
    }
}

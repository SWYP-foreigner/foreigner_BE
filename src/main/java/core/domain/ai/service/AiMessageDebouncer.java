package core.domain.ai.service;

import core.domain.ai.dto.MessageCreatedEvent;
import core.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiMessageDebouncer {

    private final AiChatUserService aiChatUserService;
    private final ThreadPoolTaskScheduler taskScheduler;

    // RoomID를 키로 사용하는 저장소들
    private final Map<Long, StringBuilder> textBuffer = new ConcurrentHashMap<>(); // 메시지 내용 누적
    private final Map<Long, Set<Long>> processedMessageIds = new ConcurrentHashMap<>(); // 이미 버퍼에 넣은 메시지 ID (중복 방지)
    private final Map<Long, Set<User>> aiParticipants = new ConcurrentHashMap<>(); // 응답해야 할 AI 목록 (누락 방지)

    private final Map<Long, MessageCreatedEvent> lastEvents = new ConcurrentHashMap<>(); // 마지막 이벤트 객체 (전송용)
    private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>(); // 예약된 작업

    private static final long DEBOUNCE_DELAY_MS = 3000; // 3초 대기

    public void bufferMessage(User aiUser, MessageCreatedEvent event) {
        Long roomId = event.messageResponse().roomId();
        Long messageId = event.messageResponse().id();
        String content = event.messageResponse().originContent();

        // 1. 기존 타이머 취소 (계속 말하면 대기시간 연장)
        if (scheduledTasks.containsKey(roomId)) {
            scheduledTasks.get(roomId).cancel(false);
        }

        // 2. [중복 방지 핵심] 해당 방 버퍼에 이미 이 메시지 ID가 들어갔는지 확인
        Set<Long> msgIds = processedMessageIds.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet());

        // 메시지 ID가 처음 들어온 경우에만 텍스트를 append 함
        if (msgIds.add(messageId)) {
            textBuffer.computeIfAbsent(roomId, k -> new StringBuilder())
                    .append(content).append(" ");
        }

        // 3. [침묵 방지 핵심] 이번 턴에 참여해야 할 AI 유저를 명단에 추가 (Set이라 중복 안됨)
        // User 객체의 equals/hashCode가 ID 기반으로 잘 구현되어 있어야 함.
        // 그렇지 않다면 User 대신 UserId(Long)를 Set에 저장하고 나중에 조회해야 함.
        aiParticipants.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(aiUser);

        // 4. 이벤트 갱신
        lastEvents.put(roomId, event);

        // 5. 스케줄링 (3초 뒤 실행)
        ScheduledFuture<?> task = taskScheduler.schedule(() -> {
            processBufferedMessages(roomId);
        }, Instant.now().plusMillis(DEBOUNCE_DELAY_MS));

        scheduledTasks.put(roomId, task);
    }

    private void processBufferedMessages(Long roomId) {
        try {
            // 저장소에서 데이터 꺼내기 (remove로 비워줌)
            StringBuilder fullContentSb = textBuffer.remove(roomId);
            MessageCreatedEvent lastEvent = lastEvents.remove(roomId);
            Set<User> aisToReply = aiParticipants.remove(roomId);
            processedMessageIds.remove(roomId); // ID 기록도 초기화
            scheduledTasks.remove(roomId);

            if (fullContentSb == null || lastEvent == null || aisToReply == null || aisToReply.isEmpty()) {
                return;
            }

            String combinedMessage = fullContentSb.toString().trim();
            log.info("🧩 [Debounce] Final Logic Start | Users: {} | Content: {}", aisToReply.size(), combinedMessage);

            // 6. [전원 기회 제공] 저장된 모든 AI에게 응답 기회 부여
            for (User aiUser : aisToReply) {
                // 각 AI마다 개별적으로 판단하고 대답함 (확률 로직은 Service 내부에 있음)
                try {
                    aiChatUserService.processAiResponse(aiUser, lastEvent, combinedMessage);
                } catch (Exception e) {
                    log.error("Error processing AI response for user: {}", aiUser.getId(), e);
                }
            }

        } catch (Exception e) {
            log.error("AI Debounce Execution Error", e);
        }
    }
}
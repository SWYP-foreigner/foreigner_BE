package core.domain.aiuser.service;

import core.domain.aiuser.dto.MessageCreatedEvent;
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

    private final AiChatCoordinatorService aiChatCoordinatorService;
    private final ThreadPoolTaskScheduler taskScheduler;
    private final AiThinkingStateManager thinkingStateManager;

    private final Map<Long, StringBuilder> textBuffer = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> processedMessageIds = new ConcurrentHashMap<>();
    private final Map<Long, Set<User>> aiParticipants = new ConcurrentHashMap<>();
    private final Map<Long, MessageCreatedEvent> lastEvents = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public void bufferMessage(User aiUser, MessageCreatedEvent event, long debounceTime) {
        Long roomId = event.messageResponse().roomId();
        Long messageId = event.messageResponse().id();
        String content = event.messageResponse().originContent();

        // 1. 기존 타이머 취소
        if (scheduledTasks.containsKey(roomId)) {
            scheduledTasks.get(roomId).cancel(false);
        }

        // 2. 메시지 누적
        Set<Long> msgIds = processedMessageIds.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet());
        if (msgIds.add(messageId)) {
            textBuffer.computeIfAbsent(roomId, k -> new StringBuilder())
                    .append(content).append(" ");
        }

        // 3. 참여 AI 등록
        aiParticipants.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(aiUser);
        lastEvents.put(roomId, event);

        // 4. 스케줄링
        scheduleProcessing(roomId, debounceTime);
    }

    private void scheduleProcessing(Long roomId, long delayMs) {
        ScheduledFuture<?> task = taskScheduler.schedule(() -> {
            processBufferedMessages(roomId);
        }, Instant.now().plusMillis(delayMs));

        scheduledTasks.put(roomId, task);
    }

    /**
     * 버퍼 처리 로직 (핵심: Thinking Lock)
     */
    private void processBufferedMessages(Long roomId) {
        try {
            // 🛑 [핵심 수정] 지금 누군가 생각 중인가? (API 호출 중인가?)
            // 만약 생각 중이라면, 유저가 말을 계속 하고 있는 것으로 간주하고
            // 버퍼를 비우지 말고 1초 뒤로 미룹니다. (Reschedule)
            if (thinkingStateManager.isAnyAiThinking(roomId)) {
                log.info("⏳ [Debounce] AI is thinking... Postponing execution. | RoomId: {}", roomId);
                scheduleProcessing(roomId, 1000); // 1초 뒤 재시도
                return;
            }

            StringBuilder fullContentSb = textBuffer.remove(roomId);
            MessageCreatedEvent lastEvent = lastEvents.remove(roomId);
            Set<User> aisToReply = aiParticipants.remove(roomId);

            processedMessageIds.remove(roomId);
            scheduledTasks.remove(roomId);

            if (fullContentSb == null || lastEvent == null || aisToReply == null || aisToReply.isEmpty()) {
                return;
            }

            String combinedMessage = fullContentSb.toString().trim();

            log.info("🧩 [Debounce] Handover to Coordinator | RoomId: {} | AI Count: {} | Content: {}",
                    roomId, aisToReply.size(), combinedMessage);

            aiChatCoordinatorService.coordinateReplies(roomId, aisToReply, lastEvent, combinedMessage);

        } catch (Exception e) {
            log.error("AI Debounce Execution Error", e);
        }
    }
}
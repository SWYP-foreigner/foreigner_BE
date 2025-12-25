package core.domain.ai.service;

import core.domain.ai.dto.MessageCreatedEvent;
import core.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiMessageDebouncer {

    private final AiChatUserService aiChatUserService;
    private final ThreadPoolTaskScheduler taskScheduler;

    // 채팅방 ID 별로 (마지막 메시지 시간, 누적된 메시지 내용, 예약된 작업) 관리
    private final Map<Long, StringBuffer> messageBuffer = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final Map<Long, MessageCreatedEvent> lastEvents = new ConcurrentHashMap<>();

    // 3초 동안 추가 메시지가 없으면 AI 발동
    private static final long DEBOUNCE_DELAY_MS = 3000;

    public void bufferMessage(User aiUser, MessageCreatedEvent event) {
        Long roomId = event.messageResponse().roomId();
        String content = event.messageResponse().originContent();

        if (scheduledTasks.containsKey(roomId)) {
            scheduledTasks.get(roomId).cancel(false);
        }
        messageBuffer.computeIfAbsent(roomId, k -> new StringBuffer()).append(content).append(" ");
        lastEvents.put(roomId, event);
        ScheduledFuture<?> task = taskScheduler.schedule(() -> {
            try {
                StringBuffer fullContent = messageBuffer.remove(roomId);
                MessageCreatedEvent lastEvent = lastEvents.remove(roomId);
                scheduledTasks.remove(roomId);

                if (fullContent != null && lastEvent != null) {
                    String combinedMessage = fullContent.toString().trim();
                    log.info("🧩 [Debounce] Merged Message: {}", combinedMessage);
                    aiChatUserService.processAiResponse(aiUser, lastEvent, combinedMessage);
                }
            } catch (Exception e) {
                log.error("AI Debounce Error", e);
            }
        }, Instant.now().plusMillis(DEBOUNCE_DELAY_MS));

        scheduledTasks.put(roomId, task);
    }
}
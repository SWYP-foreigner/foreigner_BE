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
    private final ThreadPoolTaskScheduler taskScheduler; // AppConfig에 등록 필요

    // 채팅방 ID 별로 (마지막 메시지 시간, 누적된 메시지 내용, 예약된 작업) 관리
    private final Map<Long, StringBuffer> messageBuffer = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final Map<Long, MessageCreatedEvent> lastEvents = new ConcurrentHashMap<>();

    // 3초 동안 추가 메시지가 없으면 AI 발동
    private static final long DEBOUNCE_DELAY_MS = 3000;

    public void bufferMessage(User aiUser, MessageCreatedEvent event) {
        Long roomId = event.messageResponse().roomId();
        String content = event.messageResponse().originContent();

        // 1. 기존에 예약된 AI 응답 취소 (말이 이어지는 중이므로)
        if (scheduledTasks.containsKey(roomId)) {
            scheduledTasks.get(roomId).cancel(false);
        }

        // 2. 메시지 내용 누적 (공백으로 이어붙이기)
        messageBuffer.computeIfAbsent(roomId, k -> new StringBuffer()).append(content).append(" ");

        // 3. 마지막 이벤트 정보 갱신 (Sender 정보 등 유지를 위해)
        lastEvents.put(roomId, event);

        // 4. 새로운 스케줄 예약 (3초 뒤 실행)
        ScheduledFuture<?> task = taskScheduler.schedule(() -> {
            try {
                // 누적된 메시지 꺼내기
                StringBuffer fullContent = messageBuffer.remove(roomId);
                MessageCreatedEvent lastEvent = lastEvents.remove(roomId);
                scheduledTasks.remove(roomId);

                if (fullContent != null && lastEvent != null) {
                    // 원본 이벤트의 content를 합쳐진 내용으로 교체하여 전달
                    String combinedMessage = fullContent.toString().trim();
                    log.info("🧩 [Debounce] Merged Message: {}", combinedMessage);

                    // 기존 이벤트를 기반으로 내용만 바꿔서 처리
                    // (Record는 불변이므로 실제로는 aiChatUserService에 넘길 때 문자열을 따로 넘기거나 DTO 재생성)
                    aiChatUserService.processAiResponse(aiUser, lastEvent, combinedMessage);
                }
            } catch (Exception e) {
                log.error("AI Debounce Error", e);
            }
        }, Instant.now().plusMillis(DEBOUNCE_DELAY_MS));

        scheduledTasks.put(roomId, task);
    }
}
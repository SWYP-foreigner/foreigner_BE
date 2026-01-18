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

        // 1. 기존 타이머 취소 (말이 계속 이어지면 대기시간 연장)
        if (scheduledTasks.containsKey(roomId)) {
            scheduledTasks.get(roomId).cancel(false);
        }

        // 2. [중복 방지] 해당 방 버퍼에 이미 이 메시지 ID가 들어갔는지 확인
        Set<Long> msgIds = processedMessageIds.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet());

        // 메시지 ID가 처음 들어온 경우에만 텍스트를 append 함
        if (msgIds.add(messageId)) {
            textBuffer.computeIfAbsent(roomId, k -> new StringBuilder())
                    .append(content).append(" ");
        }

        // 3. [참여 AI 등록] 이번 턴에 대답할 후보로 등록 (Set이라 중복 제거됨)
        // 나중에 Coordinator가 이 명단을 보고 순서를 정함
        aiParticipants.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(aiUser);

        // 4. 이벤트 갱신 (가장 최신 컨텍스트 유지를 위해)
        lastEvents.put(roomId, event);

        // 5. 스케줄링 (3초 뒤 실행)
        ScheduledFuture<?> task = taskScheduler.schedule(() -> {
            processBufferedMessages(roomId);
        }, Instant.now().plusMillis(DEBOUNCE_DELAY_MS));

        scheduledTasks.put(roomId, task);
    }

    /**
     * 3초간 추가 메시지가 없으면 실행되는 로직
     * 쌓인 데이터를 꺼내서 Coordinator에게 넘깁니다.
     */
    private void processBufferedMessages(Long roomId) {
        try {
            // 저장소에서 데이터 꺼내기 (remove로 비워서 상태 초기화)
            StringBuilder fullContentSb = textBuffer.remove(roomId);
            MessageCreatedEvent lastEvent = lastEvents.remove(roomId);
            Set<User> aisToReply = aiParticipants.remove(roomId);

            // 정리 작업
            processedMessageIds.remove(roomId);
            scheduledTasks.remove(roomId);

            // 유효성 검사
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
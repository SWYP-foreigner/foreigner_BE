package core.global.ai.listener;

import core.domain.chat.dto.MessageSentEvent;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.ai.dto.MessageCreatedEvent;
import core.global.ai.service.AiChatUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiReplyEventListener {

    private final UserRepository userRepository;
    private final AiChatUserService aiChatService;

    // 비동기 딜레이를 위한 스케줄러 (스레드 풀 10개)
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(10);
    private final Random random = new Random();

    @Async
    @EventListener
    public void handleMessageSent(MessageCreatedEvent event) {
        // 1. 이 채팅방의 '나를 제외한 모든 참여자' 찾기 (단톡방 대응)
        List<User> participants = userRepository.findPartnersByChatRoomId(
                event.messageResponse().roomId(), // roomId 가져오는 방식 확인 필요 (event.roomSummary().roomId() or event.messageResponse().roomId())
                event.messageResponse().senderId()
        );

        if (participants == null || participants.isEmpty()) {
            return;
        }

        // 2. 참여자 목록을 순회하며 AI 봇만 골라내서 각각 반응 스케줄링
        for (User receiver : participants) {

            // AI 판별 로직 (provider가 AI_BOT 인 경우만)
            if (!"AI_BOT".equals(receiver.getProvider())) {
                continue; // 사람이면 스킵
            }

            // 3. 인간적인 지연 시간 계산 (Smart Delay)
            // 봇마다 랜덤성이 조금씩 달라야 동시에 말하지 않음
            int lengthDelay = event.messageResponse().originContent().length() / 10;
            long totalDelay = 2L + lengthDelay + random.nextInt(4); // 최소 2초 ~ 최대 N초

            if (totalDelay > 15) totalDelay = 15; // 너무 늦지 않게 제한

            log.info("🤖 AI [ID:{}, 이름:{}] 감지됨 (단톡방). {}초 후 응답 예정.",
                    receiver.getId(), receiver.getFirstName(), totalDelay);

            // 4. 각 AI별로 스케줄링 등록
            scheduler.schedule(() -> {
                try {
                    aiChatService.processAiResponse(receiver, event);
                } catch (Exception e) {
                    log.error("AI [ID:{}] 응답 프로세스 실패", receiver.getId(), e);
                }
            }, totalDelay, TimeUnit.SECONDS);
        }
    }
}
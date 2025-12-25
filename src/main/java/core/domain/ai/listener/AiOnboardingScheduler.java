package core.domain.ai.listener;

import core.domain.ai.service.AiOnboardingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiOnboardingScheduler {

    private final AiOnboardingService aiOnboardingService;

    /**
     * 1분마다 실행 (60,000ms)
     * 신규 유저를 체크하고 타이밍(10분, 20분...)이 맞으면 AI가 말을  겁니다.
     */
    @Scheduled(fixedDelay = 60000)
    public void runAiOnboarding() {

        try {
            aiOnboardingService.sendWelcomeMessagesToNewUsers();
        } catch (Exception e) {
            log.error("❌ [Scheduler] Error during AI onboarding process", e);
        }
    }
}
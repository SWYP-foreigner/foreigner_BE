package core.domain.user.service;


import core.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserMetricsScheduler {

    private final UserRepository userRepository;

    /**
     * 매일 새벽 4시에 전체 유저의 응답률(reply_rate)을 갱신합니다.
     * 트래픽이 가장 적은 시간에 수행하여 DB 부하를 최소화합니다.
     */
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void updateAllUserReplyRates() {
        log.info("=== [Scheduler] 유저 응답률(Reply Rate) 계산 시작 ===");
        long start = System.currentTimeMillis();

        try {
            userRepository.updateReplyRatesBulk();
        } catch (Exception e) {
            log.error("응답률 갱신 중 오류 발생", e);
        }

        long end = System.currentTimeMillis();
        log.info("=== [Scheduler] 유저 응답률 계산 완료 (소요시간: {}ms) ===", (end - start));
    }
}
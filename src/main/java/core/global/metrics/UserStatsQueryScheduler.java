package core.global.metrics;

import core.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserStatsQueryScheduler {

    private final UserRepository userRepository;
    private final UserMetrics userMetrics;

    /**
     * 5분마다 DAU/WAU/MAU 스냅샷 갱신
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT30S")
    @Transactional(readOnly = true)
    public void refreshUserMetrics() {
        try {
            int dau = (int) userRepository.countActiveUsersLast1Day();
            int wau = (int) userRepository.countActiveUsersLast7Days();
            int mau = (int) userRepository.countActiveUsersLast30Days();

            // ACU/MCU는 아직 별도 로직 없으니 0으로 유지 (나중에 presence 기반으로 채워도 됨)
            userMetrics.update(dau, wau, mau, 0, 0);
        } catch (Exception e) {
            log.error("[UserStats] refreshUserMetrics failed", e);
        }
    }
}

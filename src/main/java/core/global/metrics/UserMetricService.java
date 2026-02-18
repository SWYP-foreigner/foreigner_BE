package core.global.metrics;

import core.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserMetricService {

    private final UserRepository userRepository;

    /**
     * 트랜잭션 내에서 안전하게 데이터를 조회합니다.
     */
    @Transactional(readOnly = true)
    public Object[] getInactiveAndTotalCounts() {
        List<Object[]> results = userRepository.countInactive30dAndTotal();

        if (results != null && !results.isEmpty()) {
            return results.get(0);
        }

        return new Object[0];
    }

    @Transactional(readOnly = true)
    public List<Object[]> getLastSeenHourDistribution() {
        return userRepository.lastSeenHourDist7d();
    }
}
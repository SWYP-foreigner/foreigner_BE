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
        Object res = userRepository.countInactive30dAndTotal();
        // 리포지토리 결과가 단일 객체일 경우 배열로 변환하는 로직 포함 가능
        return toRow(res);
    }

    @Transactional(readOnly = true)
    public List<Object[]> getLastSeenHourDistribution() {
        return userRepository.lastSeenHourDist7d();
    }

    private Object[] toRow(Object res) {
        if (res instanceof Object[]) return (Object[]) res;
        return new Object[]{res};
    }
}
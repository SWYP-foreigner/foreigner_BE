package core.domain.user.service;

import core.domain.user.repository.UserRepository;
import core.global.metrics.ReactivationCounter;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class UserActivityService {

    private final UserRepository userRepository;
    private final ReactivationCounter reactivationCounter;

    @Async
    @Transactional
    public void updateLastSeenAt(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            Instant prev = user.getLastSeenAt();
            boolean wasDormant = (prev == null) || prev.isBefore(Instant.now().minus(30, ChronoUnit.DAYS));

            if (user.getLastSeenAt() == null ||
                ChronoUnit.MINUTES.between(user.getLastSeenAt(), Instant.now()) > 5) {
                user.updateLastSeenAt(); // 기존 메서드 그대로
            }

            if (wasDormant) {
                reactivationCounter.inc(); // ← 30d+ 미접속 → 이번에 복귀 이벤트
            }
        });
    }
    @Transactional
    public void recordVisit(Long userId) {
        userRepository.findById(userId).ifPresent(user -> {
            // 마지막 접속이 오늘 이전이라면(혹은 1시간 전) 방문 횟수 증가 로직 등 추가 가능
            // 여기서는 심플하게 접속 시 무조건 증가 (필요시 시간 체크 로직 추가)
            user.incrementVisitCount();
            user.updateLastSeenAt();
        });
    }

    @Transactional
    public void addActivityPoint(Long userId, long points) {
        userRepository.findById(userId).ifPresent(user -> {
            user.addActivityPoint(points);
        });
    }
}

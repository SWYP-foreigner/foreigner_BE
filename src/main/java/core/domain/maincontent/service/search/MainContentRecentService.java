package core.domain.maincontent.service.search;

import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MainContentRecentService {
    private static final int MAX = 8;
    private static final Duration TTL = Duration.ofDays(180);
    private final StringRedisTemplate redis;
    private final UserRepository userRepository;

    private static String keyOf(Long userId) {
        return "recent:main:" + userId;
    }

    public void log(String raw) {
        if (raw == null) return;
        String q = raw.trim();
        if (q.isEmpty()) return;
        q = q.toLowerCase(Locale.ROOT);

        Long userId = getUserId();
        String key = keyOf(userId);
        double now = System.currentTimeMillis();

        // Redis ZSet 저장 (중복 시 스코어 업데이트로 최상단 이동)
        redis.opsForZSet().add(key, q, now);

        // 트림 (최대 MAX 개수 유지)
        Long size = redis.opsForZSet().zCard(key);
        if (size != null && size > MAX) {
            long removeCount = size - MAX;
            redis.opsForZSet().removeRange(key, 0, removeCount - 1);
        }

        redis.expire(key, TTL);
    }

    public List<String> list() {
        String key = keyOf(getUserId());
        Set<String> s = redis.opsForZSet().reverseRange(key, 0, MAX - 1);
        if (s == null || s.isEmpty()) return List.of();

        return new ArrayList<>(s);
    }

    public void remove(String q) {
        if (q == null) return;
        redis.opsForZSet().remove(keyOf(getUserId()), q.trim().toLowerCase(Locale.ROOT));
    }

    public void clear() {
        redis.delete(keyOf(getUserId()));
    }

    private Long getUserId() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        return user.getId();
    }
}
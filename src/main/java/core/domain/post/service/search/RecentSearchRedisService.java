package core.domain.post.service.search;

import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.exception.BusinessException;
import core.global.enums.errorcode.UserErrorCode;
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
public class RecentSearchRedisService {

    private static final int MAX = 10;                    // ✅ 사용자당 최대 보관 개수 8개
    private static final Duration TTL = Duration.ofDays(180);
    private final StringRedisTemplate redis;
    private final UserRepository userRepository;

    private static String keyOf(Long userId) {
        return "recent:" + userId;
    }

    public void log(String raw) {
        if (raw == null) return;
        String q = raw.trim();
        if (q.isEmpty()) return;
        q = q.toLowerCase(Locale.ROOT);

        // 내부 메서드를 호출하여 현재 로그인한 유저 ID를 직접 획득
        Long userId = getUserId();
        String key = keyOf(userId);
        double now = System.currentTimeMillis();

        // Redis ZSet 저장 (중복 시 스코어/시간만 업데이트됨)
        redis.opsForZSet().add(key, q, now);

        // 트림 (최대 MAX 개수 유지)
        Long size = redis.opsForZSet().zCard(key);
        if (size != null && size > MAX) {
            long removeCount = size - MAX;
            redis.opsForZSet().removeRange(key, 0, removeCount - 1);
        }

        // TTL 연장
        redis.expire(key, TTL);
    }

    public List<String> list() {
        String key = keyOf(getUserId());

        int end = MAX - 1;

        Set<String> s = redis.opsForZSet().reverseRange(key, 0, end); // 최신순
        if (s == null || s.isEmpty()) return List.of();

        // 순서 유지용 복사
        return new ArrayList<>(s);
    }

    public void remove(String q) {
        redis.opsForZSet().remove(keyOf(getUserId()), q.toLowerCase(Locale.ROOT));
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
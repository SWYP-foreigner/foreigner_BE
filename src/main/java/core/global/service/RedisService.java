package core.global.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RedisService {
    @Value("${redis.key.prefix.refreshToken}")
    private String refreshTokenPrefix;

    @Value("${redis.key.prefix.blacklist}")
    private String blacklistPrefix;
    private final StringRedisTemplate redisTemplate;


    public void saveRefreshToken(Long userId, String refreshToken, long expiration) {
        String key = getRefreshTokenKey(userId);
        redisTemplate.opsForValue().set(key, refreshToken, expiration, TimeUnit.MILLISECONDS);
    }
    public String getRefreshToken(Long userId) {
        return redisTemplate.opsForValue().get(getRefreshTokenKey(userId));
    }

    public void deleteRefreshToken(Long userId) {
        redisTemplate.delete(getRefreshTokenKey(userId));
    }

    public void blacklistAccessToken(String accessToken, long expiration) {
        String key = getBlacklistKey(accessToken);
        redisTemplate.opsForValue().set(key, blacklistPrefix, expiration, TimeUnit.MILLISECONDS);
    }

    public boolean isBlacklisted(String accessToken) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(getBlacklistKey(accessToken)));
    }

    private String getRefreshTokenKey(Long userId) {
        return refreshTokenPrefix + userId;
    }

    private String getBlacklistKey(String token) {
        return blacklistPrefix + token;
    }
}

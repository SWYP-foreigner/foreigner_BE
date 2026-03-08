package core.global.redis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisService {

    private final StringRedisTemplate redisTemplate;
    @Value("${spring.data.redis.key.prefix.refreshToken}")
    private String refreshTokenPrefix;

    @Value("${spring.data.redis.key.prefix.blacklist}")
    private String blacklistPrefix;

    /**
     * Refresh Token 저장..
     * @param userId 사용자 ID
     * @param refreshToken 저장할 리프레시 토큰
     * @param expirationMillis 토큰 만료 시간 (밀리초)
     */
    public void saveRefreshToken(Long userId, String refreshToken, long expirationMillis) {
        String key = getRefreshTokenKey(userId);
        redisTemplate.opsForValue().set(key, refreshToken, expirationMillis, TimeUnit.MILLISECONDS);
        String storedToken = redisTemplate.opsForValue().get(key);
    }


    /**
     * Refresh Token 조회
     * @param userId 사용자 ID
     * @return Redis에 저장된 리프레시 토큰
     */
    public String getRefreshToken(Long userId) {
        return redisTemplate.opsForValue().get(getRefreshTokenKey(userId));
    }

    /**
     * Refresh Token 삭제
     * @param userId 사용자 ID
     */
    public void deleteRefreshToken(Long userId) {
        redisTemplate.delete(getRefreshTokenKey(userId));
    }

    /**
     * Access Token 블랙리스트 등록
     * @param accessToken 블랙리스트에 등록할 액세스 토큰
     * @param expirationMillis 토큰 만료 시간 (밀리초)
     */
    public void blacklistAccessToken(String accessToken, long expirationMillis) {
        String key = getBlacklistKey(accessToken);
        redisTemplate.opsForValue().set(key, "true", expirationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 블랙리스트 여부 확인
     * @param accessToken 확인할 액세스 토큰
     * @return 블랙리스트에 등록되어 있으면 true, 아니면 false
     */
    public boolean isBlacklisted(String accessToken) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(getBlacklistKey(accessToken)));
    }

    /**
     * 리프레시 토큰 키 생성
     */
    private String getRefreshTokenKey(Long userId) {
        return refreshTokenPrefix + userId;
    }

    /**
     * 블랙리스트 키 생성
     */
    private String getBlacklistKey(String token) {
        return blacklistPrefix + token;
    }

    /**
     * 일반 데이터 저장 (만료 시간 포함)
     * @param key 저장할 키 (예: admin:otp-reset:email)
     * @param value 저장할 값 (예: 인증코드)
     * @param durationMillis 만료 시간(밀리초)
     */
    public void setDataExpire(String key, String value, long durationMillis) {
        redisTemplate.opsForValue().set(key, value, durationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 일반 데이터 조회
     */
    public String getData(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 일반 데이터 삭제
     */
    public void deleteData(String key) {
        redisTemplate.delete(key);
    }


    public void addSetElement(String key, String value) {
        redisTemplate.opsForSet().add(key, value);
    }

    public void removeSetElement(String key, String value) {
        redisTemplate.opsForSet().remove(key, value);
    }

    public Set<String> getSetElements(String key) {
        return redisTemplate.opsForSet().members(key);
    }
}

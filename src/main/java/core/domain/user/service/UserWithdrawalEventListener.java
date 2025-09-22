package core.domain.user.service;

import core.domain.user.dto.UserWithdrawalEvent;
import core.global.config.JwtTokenProvider;
import core.global.service.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// UserWithdrawalEventListener.java
@Component
@Slf4j
public class UserWithdrawalEventListener {

    private final RedisService redisService;
    private final JwtTokenProvider jwtTokenProvider;


    public UserWithdrawalEventListener(RedisService redisService, JwtTokenProvider jwtTokenProvider) {
        this.redisService = redisService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @TransactionalEventListener
    public void handleUserWithdrawal(UserWithdrawalEvent event) {
        try {
            log.info(">>>> DB-Transaction committed. Starting Redis cleanup for user ID: {}", event.getUserId());
            redisService.deleteRefreshToken(event.getUserId());

            long expiration = jwtTokenProvider.getExpiration(event.getAccessToken()).getTime() - System.currentTimeMillis();
            redisService.blacklistAccessToken(event.getAccessToken(), expiration);
            log.info(">>>> Redis cleanup finished for user ID: {}", event.getUserId());
        } catch (Exception e) {
            log.error("회원 탈퇴 후 Redis 정리 작업 실패. 수동 확인 필요. User ID: {}", event.getUserId(), e);
        }
    }
}
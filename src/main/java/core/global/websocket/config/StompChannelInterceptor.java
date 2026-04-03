package core.global.websocket.config;

import core.domain.user.service.UserActivityService;
import core.global.config.CustomUserDetails;
import core.global.enums.errorcode.AuthErrorCode;
import core.global.metrics.ChatMetrics;
import core.global.metrics.ChatRoomDwellRecorder;
import core.global.redis.service.RedisService;
import core.global.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class StompChannelInterceptor implements ChannelInterceptor {

    private final JwtTokenProvider jwtTokenProvider;
    private final RedisService redisService;
    private final UserActivityService userActivityService;
    private final ChatRoomDwellRecorder dwell;
    private final ChatMetrics chatMetrics;

    // Redis에 저장할 키 (전역적으로 관리)
    public static final String ACTIVE_USERS_KEY = "chat:active_users";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        StompCommand command = accessor.getCommand();

        // 1. CONNECT: 유저가 들어올 때 Redis Set에 추가
        if (StompCommand.CONNECT.equals(command)) {
            handleConnect(accessor);
        }
        // 2. DISCONNECT: 유저가 나갈 때 Redis Set에서 제거
        else if (StompCommand.DISCONNECT.equals(command)) {
            handleDisconnect(accessor);
        }
        // 3. SUBSCRIBE / UNSUBSCRIBE (기존 로직 유지)
        else if (StompCommand.SUBSCRIBE.equals(command)) {
            dwell.onEnter(accessor.getSessionId(), parseRoomId(accessor.getDestination()));
        } else if (StompCommand.UNSUBSCRIBE.equals(command) || StompCommand.DISCONNECT.equals(command)) {
            dwell.onLeave(accessor.getSessionId());
        }

        return message;
    }

    private void handleConnect(StompHeaderAccessor accessor) {
        // [부하 테스트 허용 로직 유지]
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try {
                String token = authHeader.substring(7);
                Long userId = jwtTokenProvider.getUserIdFromAccessToken(token);

                // 인증 및 세션 설정
                setSessionAttributes(accessor, userId, jwtTokenProvider.getEmailFromToken(token));

                // [핵심] Redis 접속자 명단에 추가
                redisService.addSetElement(ACTIVE_USERS_KEY, userId.toString());

            } catch (Exception e) {
                log.error("❌ [WS Connect Failed]: {}", e.getMessage());
            }
        }
    }

    private void handleDisconnect(StompHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null && sessionAttributes.get("userId") != null) {
            Long userId = (Long) sessionAttributes.get("userId");

            // [핵심] Redis 접속자 명단에서 제거
            redisService.removeSetElement(ACTIVE_USERS_KEY, userId.toString());
        }
        chatMetrics.onWsDisconnect("normal");
    }

    private void setSessionAttributes(StompHeaderAccessor accessor, Long userId, String email) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null) {
            sessionAttributes.put("userId", userId);
            userActivityService.updateLastSeenAt(email);
        }
    }

    private String parseRoomId(String dest) {
        if (dest == null) return "unknown";
        String[] parts = dest.split("/");
        return parts.length > 0 ? parts[parts.length - 1] : "unknown";
    }
}
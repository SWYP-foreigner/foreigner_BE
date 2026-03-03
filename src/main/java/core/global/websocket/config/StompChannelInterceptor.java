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

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            log.info(" 부하 테스트를 위해 CONNECT 인증을 일시 허용합니다.");
            return message;
        }

        String destination = accessor.getDestination();
        if (destination != null && destination.startsWith("/app/chat.sendMessageBad")) {
            return message;
        }
        /*부하 테스트 끝나고 지워야함
        * */
        if (accessor == null) {
            accessor = StompHeaderAccessor.wrap(message);
        }

        // 1. CONNECT (연결 시)
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");

            // 토큰 유효성 검사 (필수)
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                throw new BadCredentialsException(AuthErrorCode.JWT_TOKEN_INVALID.getMessage());
            }

            String token = authHeader.substring(7);
            try {
                if (redisService.isBlacklisted(token)) {
                    throw new BadCredentialsException(AuthErrorCode.JWT_TOKEN_BLACKLISTED.getMessage());
                }
                if (!jwtTokenProvider.validateToken(token)) {
                    throw new BadCredentialsException(AuthErrorCode.JWT_TOKEN_INVALID.getMessage());
                }

                Long userId = jwtTokenProvider.getUserIdFromAccessToken(token);
                String email = jwtTokenProvider.getEmailFromToken(token);

                // 인증 객체 생성
                CustomUserDetails principal = new CustomUserDetails(userId, email, new ArrayList<>());
                Authentication auth = new UsernamePasswordAuthenticationToken(principal, token, principal.getAuthorities());

                // 세션에 인증 정보 저장
                accessor.setUser(auth);

                Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
                if (sessionAttributes != null) {
                    sessionAttributes.put("userAuth", auth);
                    sessionAttributes.put("userId", userId);
                    sessionAttributes.put("connectAt", System.currentTimeMillis());

                    // 접속 기록 및 활동 점수 업데이트
                    userActivityService.updateLastSeenAt(email);
                    userActivityService.recordVisit(userId);
                }

                // [로그 추가] 정상 연결 로그
                log.info("🔌 [WS Connect] User Connected - ID: {}, Email: {}", userId, email);

                chatMetrics.onWsConnect("normal");

            } catch (Exception e) {
                log.error("❌ [WS Connect Failed] JWT Error: {}", e.getMessage());
                throw new BadCredentialsException(AuthErrorCode.JWT_TOKEN_INVALID.getMessage());
            }

        }
        // 2. SEND (메시지 전송 시)
        else if (StompCommand.SEND.equals(accessor.getCommand())) {
            Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
            if (sessionAttributes != null && sessionAttributes.get("userId") != null) {
                try {
                    userActivityService.addActivityPoint((Long) sessionAttributes.get("userId"), 5L);
                } catch (Exception e) { /* 무시 */ }
            }
        }
        // 3. SUBSCRIBE / DISCONNECT 등
        else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            dwell.onEnter(accessor.getSessionId(), parseRoomId(accessor.getDestination()));
        } else if (StompCommand.DISCONNECT.equals(accessor.getCommand())) {
            dwell.onLeave(accessor.getSessionId());
            chatMetrics.onWsDisconnect("normal");
        } else if (StompCommand.UNSUBSCRIBE.equals(accessor.getCommand())) {
            dwell.onLeave(accessor.getSessionId());
        }

        return message;
    }

    private String parseRoomId(String dest) {
        if (dest == null) return "unknown";
        String[] parts = dest.split("/");
        return parts.length > 0 ? parts[parts.length - 1] : "unknown";
    }
}
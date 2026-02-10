package core.global.websocket.config;

import core.domain.user.service.UserActivityService;
import core.global.config.CustomUserDetails;
import core.global.enums.errorcode.AuthErrorCode;
import core.global.metrics.ChatMetrics;
import core.global.security.JwtTokenProvider;
import core.global.metrics.ChatRoomDwellRecorder;
import core.global.redis.service.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
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
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        log.debug("preSend 진입: command={}, destination={}", accessor.getCommand(), accessor.getDestination());

        // 1. CONNECT (연결 시)
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            log.info("STOMP CONNECT 요청 처리 시작");
            String authHeader = accessor.getFirstNativeHeader("Authorization");

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("STOMP CONNECT Authorization 헤더 없음 또는 Bearer 형식 아님");
                throw new BadCredentialsException(AuthErrorCode.JWT_TOKEN_NOT_FOUND.getMessage());
            }
            String token = authHeader.substring(7);

            try {
                if (redisService.isBlacklisted(token)) {
                    log.warn("STOMP JWT 토큰이 블랙리스트에 있습니다.");
                    throw new BadCredentialsException(AuthErrorCode.JWT_TOKEN_BLACKLISTED.getMessage());
                }
                if (!jwtTokenProvider.validateToken(token)) {
                    log.warn("STOMP JWT 토큰이 유효하지 않습니다.");
                    throw new BadCredentialsException(AuthErrorCode.JWT_TOKEN_INVALID.getMessage());
                }

                String email = jwtTokenProvider.getEmailFromToken(token);
                Long userId = jwtTokenProvider.getUserIdFromAccessToken(token);

                CustomUserDetails principal = new CustomUserDetails(userId, email, new ArrayList<>());
                Authentication auth = new UsernamePasswordAuthenticationToken(principal, token, principal.getAuthorities());

                Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
                if (sessionAttributes != null) {
                    sessionAttributes.put("userAuth", auth);
                    sessionAttributes.put("userId", userId);
                    sessionAttributes.put("connectAt", System.currentTimeMillis());

                    String userEmail = auth.getName();
                    userActivityService.updateLastSeenAt(userEmail); // 기존: 휴면 복구 및 접속 시간 갱신

                    // [추가 1] 방문 횟수 증가 (Visit Count)
                    userActivityService.recordVisit(userId);
                }
                accessor.setUser(auth);
                log.info("STOMP JWT 인증 완료: WebSocket 세션에 사용자 정보 등록 (userId: {})", userId);

                chatMetrics.onWsConnect("normal");

            } catch (Exception e) {
                log.error("STOMP JWT 처리 중 예외 발생: {}", e.getMessage(), e);
                chatMetrics.onWsConnect("auth_error");
                throw new BadCredentialsException(AuthErrorCode.JWT_TOKEN_INVALID.getMessage());
            }

        }
        // 2. SEND (메시지 전송 시)
        else if (StompCommand.SEND.equals(accessor.getCommand())) {

            // [추가 2] 채팅 메시지 전송 시 활동 포인트 적립
            Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
            if (sessionAttributes != null) {
                Long userId = (Long) sessionAttributes.get("userId");
                if (userId != null) {
                    // 채팅 1회당 5점 부여 (정책에 따라 조절)
                    userActivityService.addActivityPoint(userId, 5L);
                }
            }

        }
        // 3. SUBSCRIBE (채팅방 입장 시)
        else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            String sessionId = accessor.getSessionId();
            String dest = accessor.getDestination();
            String roomId = parseRoomId(dest);
            dwell.onEnter(sessionId, roomId);

        } else if (StompCommand.DISCONNECT.equals(accessor.getCommand())) {

            Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
            if (sessionAttributes != null) {
                Long userId = (Long) sessionAttributes.get("userId");
                Object startedObj = sessionAttributes.get("connectAt");
                long start = (startedObj instanceof Number n) ? n.longValue() : 0L;
                long now = System.currentTimeMillis();
                if (start > 0 && now >= start) {
                    dwell.onLeave(accessor.getSessionId());
                }
            }
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
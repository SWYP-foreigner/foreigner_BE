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
        // 디버깅 로그가 너무 많으면 성능 저하되므로 debug 레벨로 유지
        log.debug("preSend 진입: command={}, destination={}", accessor.getCommand(), accessor.getDestination());

        // 1. CONNECT (연결 시)
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");

            // =================================================================
            // 🚨 [수정] 부하 테스트용 백도어 (토큰 없으면 테스트 유저로 통과)
            // =================================================================
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.info("🚧 [LoadTest] Authorization 헤더 없음 -> 테스트 유저로 접속 허용");

                // k6에서 'user-id' 헤더를 보내주면 좋지만, 없으면 랜덤/기본값 사용
                // (일단 에러 안 나게 임의의 ID 부여)
                String headerUserId = accessor.getFirstNativeHeader("user-id");
                Long userId = (headerUserId != null) ? Long.valueOf(headerUserId) : 99999L;
                String email = "loadtest_" + userId + "@test.com";

                // 가짜 인증 객체 생성
                CustomUserDetails principal = new CustomUserDetails(userId, email, new ArrayList<>());
                Authentication auth = new UsernamePasswordAuthenticationToken(principal, "TEST_TOKEN", principal.getAuthorities());

                Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
                if (sessionAttributes != null) {
                    sessionAttributes.put("userAuth", auth);
                    sessionAttributes.put("userId", userId);
                    sessionAttributes.put("connectAt", System.currentTimeMillis());

                    // DB 기록 시도 (없는 유저일 수 있으므로 에러 무시)
                    try {
                        userActivityService.updateLastSeenAt(email);
                        userActivityService.recordVisit(userId);
                    } catch (Exception e) {
                        log.warn("🚧 [LoadTest] DB 통계 집계 실패 (무시함): {}", e.getMessage());
                    }
                }

                accessor.setUser(auth);
                chatMetrics.onWsConnect("load_test");
                return message; // 👈 여기서 바로 리턴 (아래 토큰 검증 로직 건너뜀)
            }
            // =================================================================

            // [기존 로직] 토큰 검증
            log.info("STOMP CONNECT 요청 처리 시작");
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
                    userActivityService.updateLastSeenAt(userEmail);
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

            Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
            if (sessionAttributes != null) {
                Long userId = (Long) sessionAttributes.get("userId");
                if (userId != null) {
                    // 활동 포인트 적립 (DB 에러나도 메시지는 가도록 try-catch 권장)
                    try {
                        userActivityService.addActivityPoint(userId, 5L);
                    } catch (Exception e) {
                        log.warn("활동 포인트 적립 실패 (무시): {}", e.getMessage());
                    }
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
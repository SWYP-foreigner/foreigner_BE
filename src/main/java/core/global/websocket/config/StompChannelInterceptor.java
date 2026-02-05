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
import org.springframework.messaging.support.MessageHeaderAccessor; // ★ 이 import 필수!
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
        // [수정 1] wrap 대신 getAccessor 사용 (원본 메시지 헤더에 접근하기 위함)
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        // 방어 로직: 혹시나 accessor가 null이면 wrap으로 생성 (보통 null 안 뜸)
        if (accessor == null) {
            accessor = StompHeaderAccessor.wrap(message);
        }

        // 1. CONNECT (연결 시)
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");

            // =================================================================
            // 🚨 [LoadTest] 부하 테스트용 백도어 (토큰 없으면 테스트 유저로 통과)
            // =================================================================
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.info("🚧 [LoadTest] Authorization 헤더 없음 -> 테스트 유저로 접속 허용");

                // K6에서 보낸 'user-id' 헤더 확인
                String headerUserId = accessor.getFirstNativeHeader("user-id");
                Long userId = (headerUserId != null) ? Long.valueOf(headerUserId) : 99999L;
                String email = "loadtest_" + userId + "@test.com";

                // [중요] 이름표(Principal)를 ID와 똑같이 만듦 (FastSocketSender가 찾기 쉽게)
                CustomUserDetails principal = new CustomUserDetails(userId, email, new ArrayList<>());

                // 익명 클래스로 getName()을 오버라이딩하여 "ID문자열"을 리턴하게 함
                Authentication auth = new UsernamePasswordAuthenticationToken(principal, "TEST_TOKEN", principal.getAuthorities()) {
                    @Override
                    public String getName() {
                        return String.valueOf(userId); // ★ 핵심: "2605" 같은 문자열 리턴
                    }
                };

                // [수정 2] 세션에 유저 정보 확실히 등록 (이게 없으면 접속자 0명 뜸)
                accessor.setUser(auth);

                // 세션 속성에도 저장 (통계용)
                Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
                if (sessionAttributes != null) {
                    sessionAttributes.put("userAuth", auth);
                    sessionAttributes.put("userId", userId);
                    sessionAttributes.put("connectAt", System.currentTimeMillis());
                }

                chatMetrics.onWsConnect("load_test");
                return message; // 검증 로직 건너뛰고 바로 통과
            }
            // =================================================================

            // [기존 로직] 일반 유저 토큰 검증
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
                CustomUserDetails principal = new CustomUserDetails(userId, email, new ArrayList<>());

                Authentication auth = new UsernamePasswordAuthenticationToken(principal, token, principal.getAuthorities());

                // 일반 유저도 setUser 필수
                accessor.setUser(auth);

                Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
                if (sessionAttributes != null) {
                    sessionAttributes.put("userAuth", auth);
                    sessionAttributes.put("userId", userId);
                    sessionAttributes.put("connectAt", System.currentTimeMillis());

                    userActivityService.updateLastSeenAt(email);
                    userActivityService.recordVisit(userId);
                }

                chatMetrics.onWsConnect("normal");

            } catch (Exception e) {
                log.error("STOMP JWT Error: {}", e.getMessage());
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
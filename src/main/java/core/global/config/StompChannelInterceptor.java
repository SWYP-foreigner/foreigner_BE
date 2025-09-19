package core.global.config;

import core.global.enums.ErrorCode;
import core.global.service.RedisService;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class StompChannelInterceptor implements ChannelInterceptor {

    private final JwtTokenProvider jwtTokenProvider;
    private final RedisService redisService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        log.debug("preSend 진입: command={}, destination={}", accessor.getCommand(), accessor.getDestination());

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            log.info("STOMP CONNECT 요청 처리 시작");
            String authHeader = accessor.getFirstNativeHeader("Authorization");

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("STOMP CONNECT Authorization 헤더 없음 또는 Bearer 형식 아님");
                throw new BadCredentialsException(ErrorCode.JWT_TOKEN_NOT_FOUND.getMessage());
            }
            String token = authHeader.substring(7);

            try {
                // 토큰 유효성 검증 로직 (기존과 동일)
                if (redisService.isBlacklisted(token)) {
                    log.warn("STOMP JWT 토큰이 블랙리스트에 있습니다.");
                    throw new BadCredentialsException(ErrorCode.JWT_TOKEN_BLACKLISTED.getMessage());
                }
                if (!jwtTokenProvider.validateToken(token)) {
                    log.warn("STOMP JWT 토큰이 유효하지 않습니다.");
                    throw new BadCredentialsException(ErrorCode.JWT_TOKEN_INVALID.getMessage());
                }

                String email = jwtTokenProvider.getEmailFromToken(token);
                Long userId = jwtTokenProvider.getUserIdFromAccessToken(token);

                CustomUserDetails principal = new CustomUserDetails(userId, email, new ArrayList<>());
                Authentication auth = new UsernamePasswordAuthenticationToken(principal, token, principal.getAuthorities());

                // [수정 1] 세션 속성에 직접 인증 정보 저장
                // accessor.setUser()가 불안정하게 동작하는 문제를 해결하기 위해 세션 속성에 직접 저장하는 방식을 사용합니다.
                Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
                if (sessionAttributes != null) {
                    sessionAttributes.put("userAuth", auth);
                }

                // accessor.setUser(auth); // 기존 방식도 함께 사용 가능 (다른 곳에서 필요할 수 있음)
                log.info("STOMP JWT 인증 완료: WebSocket 세션에 사용자 정보 등록 (userId: {})", userId);

            } catch (Exception e) {
                log.error("STOMP JWT 처리 중 예외 발생: {}", e.getMessage(), e);
                throw new BadCredentialsException(ErrorCode.JWT_TOKEN_INVALID.getMessage());
            }
        } else if (StompCommand.SEND.equals(accessor.getCommand()) || StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {

            // [수정 2] 세션 속성에서 직접 인증 정보 조회
            Authentication auth = null;
            Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
            if (sessionAttributes != null) {
                auth = (Authentication) sessionAttributes.get("userAuth");
            }

            if (auth != null) {
                SecurityContextHolder.getContext().setAuthentication(auth);
                log.debug("STOMP AUTHORIZED: SecurityContextHolder에 인증 정보 설정 완료, command={}", accessor.getCommand());
            } else {
                log.warn("STOMP UNAUTHORIZED: WebSocket 세션에 인증 정보가 없습니다, command={}", accessor.getCommand());

                // [수정 3] 인증되지 않은 메시지가 컨트롤러로 전달되지 않도록 차단
                // null을 반환하면 해당 메시지는 더 이상 처리되지 않고 소멸됩니다.
                return null;
            }
        }
        return message;
    }
}
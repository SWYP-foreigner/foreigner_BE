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
            // === 최초 연결 시 JWT를 검증하고 WebSocket 세션에 인증 정보 저장 ===
            log.info("STOMP CONNECT 요청 처리 시작");
            String authHeader = accessor.getFirstNativeHeader("Authorization");

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("STOMP CONNECT Authorization 헤더 없음 또는 Bearer 형식 아님");
                throw new BadCredentialsException(ErrorCode.JWT_TOKEN_NOT_FOUND.getMessage());
            }
            String token = authHeader.substring(7);

            try {
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

                accessor.setUser(auth);
                log.info("STOMP JWT 인증 완료: WebSocket 세션에 사용자 정보 등록 (userId: {})", userId);

            } catch (Exception e) {
                log.error("STOMP JWT 처리 중 예외 발생: {}", e.getMessage(), e);
                throw new BadCredentialsException(ErrorCode.JWT_TOKEN_INVALID.getMessage());
            }
        }
        else if (StompCommand.SEND.equals(accessor.getCommand()) || StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            Authentication auth = (Authentication) accessor.getUser();
            if (auth != null) {
                SecurityContextHolder.getContext().setAuthentication(auth);
                log.debug("STOMP AUTHORIZED: SecurityContextHolder에 인증 정보 설정 완료, command={}", accessor.getCommand());
            } else {
                log.warn("STOMP UNAUTHORIZED: WebSocket 세션에 인증 정보가 없습니다, command={}", accessor.getCommand());
                // 이 부분에 예외를 추가해야 합니다.
                throw new BadCredentialsException("인증 정보 없이 STOMP 메시지를 보낼 수 없습니다.");
            }
        }
        return message;
    }
}
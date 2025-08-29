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
        log.debug("preSend 진입: command={}, headers={}", accessor.getCommand(), accessor.toNativeHeaderMap());

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            log.info("STOMP CONNECT 들어옴");

            String authHeader = accessor.getFirstNativeHeader("Authorization");
            log.info("CONNECT Authorization 헤더: {}", authHeader);

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("STOMP CONNECT Authorization 헤더 없음 또는 Bearer 형식 아님");
                throw new BadCredentialsException(ErrorCode.JWT_TOKEN_NOT_FOUND.getMessage());
            }

            String token = authHeader.substring(7);
            log.info("토큰 추출 성공: {}", token);

            try {
                // 블랙리스트 체크
                if (redisService.isBlacklisted(token)) {
                    log.warn("STOMP JWT 토큰 블랙리스트에 있음");
                    throw new BadCredentialsException(ErrorCode.JWT_TOKEN_BLACKLISTED.getMessage());
                }
                log.info("블랙리스트 체크 통과");

                // 토큰 검증
                if (!jwtTokenProvider.validateToken(token)) {
                    log.warn("STOMP JWT 토큰 유효하지 않음");
                    throw new BadCredentialsException(ErrorCode.JWT_TOKEN_INVALID.getMessage());
                }
                log.info("토큰 검증 통과");

                // 토큰에서 정보 꺼내기
                String email = jwtTokenProvider.getEmailFromToken(token);
                Long userId = jwtTokenProvider.getUserIdFromAccessToken(token);
                log.info("토큰에서 정보 추출: userId={}, email={}", userId, email);

                // 인증 객체 생성
                CustomUserDetails principal = new CustomUserDetails(userId, email, new ArrayList<>());
                Authentication auth = new UsernamePasswordAuthenticationToken(principal, token, principal.getAuthorities());

                // SecurityContext에 저장
                SecurityContextHolder.getContext().setAuthentication(auth);
                accessor.setUser(auth); // WebSocket 세션에도 인증 정보 등록
                log.info("STOMP JWT 인증 완료: SecurityContext 및 WebSocket 세션 등록");

            } catch (Exception e) {
                log.error("STOMP JWT 처리 중 예외 발생: {}", e.getMessage(), e);
                throw new BadCredentialsException(ErrorCode.JWT_TOKEN_INVALID.getMessage());
            }
        } else {
            log.debug("STOMP 명령 CONNECT 아님, command={}", accessor.getCommand());
        }

        return message;
    }
}

package core.global.exception;

import core.global.dto.ApiErrorResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.ControllerAdvice;

import java.security.Principal;

@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalWebSocketExceptionHandler {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 비즈니스 예외 (ChatErrorCode 등) 처리
     */
    @MessageExceptionHandler(BusinessException.class)
    public void handleBusinessException(BusinessException ex, Principal principal, @Header("simpSessionId") String sessionId) {
        log.warn("[WebSocket Business Error] session={}, user={}, msg={}",
                sessionId, (principal != null ? principal.getName() : "Unknown"), ex.getMessage());

        ApiErrorResponse errorResponse = ApiErrorResponse.of(
                "BUSINESS_ERROR",
                ex.getError().code(),
                ex.getMessage(),
                ex.getDetail()
        );

        sendErrorToUser(principal, sessionId, errorResponse);
    }

    /**
     * 예상치 못한 런타임 예외 처리
     */
    @MessageExceptionHandler(Exception.class)
    public void handleException(Exception ex, Principal principal, @Header("simpSessionId") String sessionId) {
        log.error("[WebSocket Server Error] session={}, user={}, msg={}",
                sessionId, (principal != null ? principal.getName() : "Unknown"), ex.getMessage(), ex);

        ApiErrorResponse errorResponse = ApiErrorResponse.of(
                "INTERNAL_SERVER_ERROR",
                "SERVER_ERROR",
                "웹소켓 통신 중 알 수 없는 서버 오류가 발생했습니다."
        );

        sendErrorToUser(principal, sessionId, errorResponse);
    }

    /**
     * 클라이언트에게 에러를 전송하는 공통 메서드
     */
    private void sendErrorToUser(Principal principal, String sessionId, ApiErrorResponse errorResponse) {
        if (principal != null) {
            // 인증된 유저인 경우 (프론트엔드는 /user/queue/errors 를 구독해야 함)
            messagingTemplate.convertAndSendToUser(principal.getName(), "/queue/errors", errorResponse);
        } else {
            // 인증되지 않은 유저인 경우 세션 ID 기반 채널로 전송 (선택 사항)
            messagingTemplate.convertAndSend("/queue/errors-" + sessionId, errorResponse);
        }
    }
}
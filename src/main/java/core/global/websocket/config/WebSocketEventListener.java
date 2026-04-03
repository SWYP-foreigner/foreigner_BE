package core.global.websocket.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Slf4j
@Component
public class WebSocketEventListener {

    /** CONNECT 요청 시 */
    @Async
    @EventListener
    public void handleWebSocketConnectListener(SessionConnectEvent event) {
    }

    /** WebSocket 세션이 실제로 연결 완료되었을 때 */
    @Async
    @EventListener
    public void handleWebSocketConnected(SessionConnectedEvent event) {
    }

    /** 연결 끊김 감지 */
    @Async
    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
    }
}
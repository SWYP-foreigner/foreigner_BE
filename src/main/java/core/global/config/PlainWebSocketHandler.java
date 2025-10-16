package core.global.config;

import core.global.metrics.SocketDwellListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlainWebSocketHandler extends TextWebSocketHandler {

    private final SocketDwellListener dwell;
    private final ConcurrentHashMap<String, Long> startedAt = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("Plain WS connected: {}, URI: {}", session.getId(), session.getUri());
        long now = System.currentTimeMillis();
        startedAt.put(session.getId(), now);
        // Plain WS를 채팅 용도로 사용한다면 feature/route 고정
        dwell.onOpen(extractUserId(session), "chat", "/chat/ws");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.info("Received from {}: {}", session.getId(), payload);
        session.sendMessage(new TextMessage("Echo: " + payload));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("Plain WS disconnected: {} with status {}", session.getId(), status);
        Long start = startedAt.remove(session.getId());
        if (start != null) {
            long dur = System.currentTimeMillis() - start;
            dwell.onClose(extractUserId(session), "chat", "/chat/ws", dur);
        }
    }

    private Long extractUserId(WebSocketSession session) {
        Object v = (session.getAttributes() != null) ? session.getAttributes().get("userId") : null;
        if (v instanceof Number n) return n.longValue();
        try { return (v != null) ? Long.valueOf(String.valueOf(v)) : null; } catch (Exception ignore) { return null; }
    }
}

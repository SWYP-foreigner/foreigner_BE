package core.global.redis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RedisChatSubscriber implements MessageListener {

    private final SimpMessagingTemplate simp;

    public RedisChatSubscriber(SimpMessagingTemplate simp) {
        this.simp = simp;
    }
    private final ObjectMapper om = new ObjectMapper();

    @Override
    public void onMessage(Message message, byte[] pattern) {

        String payload = new String(message.getBody());
        try {
            var node = om.readTree(payload);
            Long roomId = node.get("roomId").asLong();
            simp.convertAndSend("/topic/room." + roomId, payload);
        } catch (Exception e) {
            log.error("Redis 메시지 파싱 및 전송 중 에러 발생. Payload: {}", payload, e);
        }
    }
}
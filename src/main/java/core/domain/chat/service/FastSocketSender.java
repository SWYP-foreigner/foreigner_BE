package core.domain.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

// [수정 1] 여기가 핵심입니다. Spring Messaging의 채널을 가져와야 합니다.
import org.springframework.messaging.MessageChannel;
// (기존 org.htmlunit... 은 지우세요!)

import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

// [수정 2] javax.inject 대신 Spring의 Qualifier를 쓰는 것이 더 일반적입니다. (물론 javax도 동작은 합니다)
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;

@Component
@RequiredArgsConstructor
public class FastSocketSender {

    private final ObjectMapper objectMapper;

    // 스프링 웹소켓의 저수준(Low-Level) 채널을 직접 주입받습니다.
    @Qualifier("clientOutboundChannel")
    private final MessageChannel clientOutboundChannel; // 이제 Spring 타입과 일치합니다.

    /**
     * [Zero-Copy 전송]
     * 객체를 JSON으로 1번만 변환한 뒤, 수신자 N명에게 바이트 배열만 복사해서 쏩니다.
     * O(N)의 직렬화 비용을 O(1)로 만듭니다.
     */
    @Async("websocketExecutor") // 가상 스레드 권장
    public void sendToUsersFast(List<Long> recipientIds, String topicSuffix, Object payload) {
        if (recipientIds == null || recipientIds.isEmpty()) return;

        try {
            // 1. [핵심] 무거운 JSON 직렬화를 루프 밖에서 딱 1번만 수행
            byte[] payloadBytes = objectMapper.writeValueAsBytes(payload);

            // 2. 가벼운 헤더만 생성하여 전송 (Payload는 재사용)
            for (Long userId : recipientIds) {
                // 프론트엔드 구독 주소: /topic/user/{userId}/{topicSuffix}
                String destination = "/topic/user/" + userId + topicSuffix;

                // STOMP 헤더 생성 (가벼움)
                SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
                accessor.setDestination(destination);
                accessor.setLeaveMutable(true);

                // Spring 내부 파싱 과정을 건너뛰고 바로 채널에 꽂아넣음
                Message<byte[]> message = MessageBuilder.createMessage(payloadBytes, accessor.getMessageHeaders());

                // [확인] 이제 send 메서드가 정상적으로 인식될 겁니다.
                clientOutboundChannel.send(message);
            }
        } catch (Exception e) {
            // 로그는 남기되, 전체 로직을 중단시키지 않음
            // (실무에서는 @Slf4j log.error 사용 권장)
            System.err.println("FastSocketSend Error: " + e.getMessage());
        }
    }
}
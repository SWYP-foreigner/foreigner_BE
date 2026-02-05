package core.domain.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry; // [필수] 이거 추가
import org.springframework.messaging.support.GenericMessage;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class FastSocketSender {

    private final MessageChannel clientOutboundChannel;
    private final ObjectMapper objectMapper;
    private final SimpUserRegistry userRegistry; // [필수] 접속자 정보 저장소

    /**
     * [Zero-Copy 전송]
     * 1. JSON 변환은 1번만 수행 (byte[])
     * 2. 유저의 세션 ID를 조회하여 헤더에 주입
     * 3. 채널에 직접 전송
     */
    public void sendToUsersFast(List<Long> recipientIds, String topicSuffix, Object payloadData) {
        if (recipientIds == null || recipientIds.isEmpty()) return;

        // 1. JSON 직렬화 (루프 밖에서 단 1회 수행 -> CPU 절약 핵심)
        byte[] payloadBytes;
        try {
            payloadBytes = objectMapper.writeValueAsBytes(payloadData);
        } catch (JsonProcessingException e) {
            log.error("JSON Serialization Failed", e);
            return;
        }

        // 2. 각 유저별로 세션 찾아서 전송 (네트워크 I/O 분산)
        for (Long userId : recipientIds) {
            // 메모리에 있는 접속자 레지스트리에서 유저 조회 (DB 조회 아님, 매우 빠름)
            SimpUser user = userRegistry.getUser(String.valueOf(userId));

            // 접속하지 않은 유저는 패스 (이 로직 덕분에 불필요한 전송 시도도 사라짐)
            if (user == null) continue;

            // 한 유저가 모바일/PC 등 여러 기기로 접속했을 수 있으므로 세션 루프
            for (SimpSession session : user.getSessions()) {
                sendToSession(session.getId(), "/topic/user/" + userId + topicSuffix, payloadBytes);
            }
        }
    }

    private void sendToSession(String sessionId, String destination, byte[] payload) {
        // 헤더 생성 (여기에 Session ID를 꼭 넣어야 함!)
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(sessionId); // <--- [핵심 해결] 에러 원인 제거
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);

        // 전송 (이미 바이트로 변환된 데이터 + 헤더)
        clientOutboundChannel.send(new GenericMessage<>(payload, accessor.getMessageHeaders()));
    }
}
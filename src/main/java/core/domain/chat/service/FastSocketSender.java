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
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class FastSocketSender {

    private final MessageChannel clientOutboundChannel;
    private final ObjectMapper objectMapper;
    private final SimpUserRegistry userRegistry;

    /**
     * [Zero-Copy 전송 + 스마트 유저 조회]
     * 1. JSON 변환 1회 수행
     * 2. Principal Name(Email) 또는 ID로 유저 조회
     * 3. 세션별 전송
     */
    public void sendToUsersFast(List<Long> recipientIds, String topicSuffix, Object payloadData) {
        if (recipientIds == null || recipientIds.isEmpty()) return;

        // 1. JSON 직렬화 (루프 밖에서 단 1회 수행 -> CPU 절약)
        byte[] payloadBytes;
        try {
            payloadBytes = objectMapper.writeValueAsBytes(payloadData);
        } catch (JsonProcessingException e) {
            log.error("JSON Serialization Failed", e);
            return;
        }

        for (Long userId : recipientIds) {
            String userIdStr = String.valueOf(userId);

            // [검색 1단계] ID로 조회 시도
            SimpUser user = userRegistry.getUser(userIdStr);

            // [검색 2단계] 없으면 Principal Name(Email) 포맷으로 재시도 (LoadTest 환경 대응)
            if (user == null) {
                String principalName = "loadtest_" + userId + "@test.com";
                user = userRegistry.getUser(principalName);
            }

            // 그래도 없으면 다음 유저로 넘어감
            if (user == null) {
                continue;
            }

            // 세션별 전송
            for (SimpSession session : user.getSessions()) {
                sendToSession(session.getId(), "/topic/user/" + userIdStr + topicSuffix, payloadBytes);
            }
        }
    }

    private void sendToSession(String sessionId, String destination, byte[] payload) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(sessionId);
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);

        clientOutboundChannel.send(new GenericMessage<>(payload, accessor.getMessageHeaders()));
    }
}
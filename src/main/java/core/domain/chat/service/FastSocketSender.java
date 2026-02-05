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
        int successCount = 0; // 카운트 추가
        int failCount = 0;    // 카운트 추가

        for (Long userId : recipientIds) {
            SimpUser user = userRegistry.getUser(String.valueOf(userId));

            // [디버깅 로그 1] 유저를 못 찾았을 때
            if (user == null) {
                // 로그가 너무 많이 뜨면 100번에 한번만 찍게 조건 걸어도 됨
                // log.warn("❌ User Not Found in Registry: {}", userId);
                failCount++;
                continue;
            }

            for (SimpSession session : user.getSessions()) {
                sendToSession(session.getId(), "/topic/user/" + userId + topicSuffix, payloadBytes);
                successCount++;
            }
        }

        // [디버깅 로그 2] 전체 결과 요약 (매우 중요)
        log.info("📢 FastSocket 결과 - 대상: {}명, 성공(세션): {}개, 실패(못찾음): {}명 | Suffix: {}",
                recipientIds.size(), successCount, failCount, topicSuffix);
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
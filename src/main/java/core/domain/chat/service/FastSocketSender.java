package core.domain.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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
    /*최적화 버전*/
/*
    public void sendToUsersFast(List<Long> recipientIds, String topicSuffix, Object payloadData) {
        if (recipientIds == null || recipientIds.isEmpty()) return;

        byte[] payloadBytes;
        try {
            payloadBytes = objectMapper.writeValueAsBytes(payloadData);
        } catch (JsonProcessingException e) {
            log.error("JSON Serialization Failed", e);
            return;
        }

        for (Long userId : recipientIds) {
            String userIdStr = String.valueOf(userId);

            SimpUser user = userRegistry.getUser(userIdStr);

            if (user == null) {
                String principalName = "loadtest_" + userId + "@test.com";
                user = userRegistry.getUser(principalName);
            }

            if (user == null) {
                continue;
            }
            for (SimpSession session : user.getSessions()) {
                sendToSession(session.getId(), "/topic/user/" + userIdStr + topicSuffix, payloadBytes);
            }
        }
    }
    */
    /*최적화 아닌 버전*/

/*
    private void sendToSession(String sessionId, String destination, byte[] payload) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(sessionId);
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);

        clientOutboundChannel.send(new GenericMessage<>(payload, accessor.getMessageHeaders()));
    }*/
}
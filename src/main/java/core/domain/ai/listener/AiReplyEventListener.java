package core.domain.ai.listener;

import core.domain.ai.dto.MessageCreatedEvent;
import core.domain.ai.service.AiMessageDebouncer;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiReplyEventListener {

    private final UserRepository userRepository;
    private final AiMessageDebouncer aiMessageDebouncer; // [핵심] 버퍼링 서비스 주입

    /**
     * 트랜잭션이 커밋된 직후(DB에 메시지가 확실히 저장된 후) 실행됩니다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMessageSent(MessageCreatedEvent event) {
        Long roomId = event.messageResponse().roomId();
        Long senderId = event.messageResponse().senderId();
        List<User> participants = userRepository.findPartnersByChatRoomId(roomId, senderId);

        if (participants == null || participants.isEmpty()) {
            return;
        }

        for (User receiver : participants) {

            if ("AI_BOT".equals(receiver.getProvider())) {
                log.info("👂 AI [ID:{}, {}] 듣는 중... (버퍼링 시작)", receiver.getId(), receiver.getFirstName());
                aiMessageDebouncer.bufferMessage(receiver, event);
            }
        }
    }
}
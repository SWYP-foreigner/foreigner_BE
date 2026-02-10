package core.domain.aiuser.listener;

import core.domain.aiuser.dto.MessageCreatedEvent;
import core.domain.aiuser.service.AiMessageDebouncer;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.Role;
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

        User sender = userRepository.findById(senderId).orElse(null);
        boolean isSenderAi = (sender != null && Role.AI.equals(sender.getUserRole()));

        List<User> participants = userRepository.findPartnersByChatRoomId(roomId, senderId);

        for (User receiver : participants) {
            if ("AI_BOT".equals(receiver.getProvider())) {
                long debounceTime = isSenderAi ? 5000L : 2500L;

                aiMessageDebouncer.bufferMessage(receiver, event, debounceTime);
            }
        }
    }
}
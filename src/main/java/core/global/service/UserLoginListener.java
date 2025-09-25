package core.global.service;

import core.global.dto.UserLoggedInEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class UserLoginListener {

    private final EventsIndexService eventsIndexService;

    @Async("esEventsExecutor") // 이미 사용 중인 실행기 재사용
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(UserLoggedInEvent e) {
        eventsIndexService.logLogin(e.userId(), e.device());
    }
}
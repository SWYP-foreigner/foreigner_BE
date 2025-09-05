package core.global.search.listener;

import core.global.search.dto.PostCreatedEvent;
import core.global.search.service.PostIndexService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class PostIndexListener {
    private final PostIndexService postIndexService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(PostCreatedEvent e) {
        postIndexService.index(e.post());
    }
}
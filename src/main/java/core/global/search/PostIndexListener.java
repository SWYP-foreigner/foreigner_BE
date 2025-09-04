package core.global.search;

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
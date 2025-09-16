package core.global.search.listener;

import core.global.search.dto.PostCreatedEvent;
import core.global.search.dto.PostDeletedEvent;
import core.global.search.dto.PostUpdatedEvent;
import core.global.search.service.PostIndexService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class PostIndexListener {
    private final PostIndexService postIndexService;

    @Async("esEventsExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(PostCreatedEvent e) {
        postIndexService.index(e.postId(), e.doc());
    }

    @Async("esEventsExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(PostUpdatedEvent e) {
        postIndexService.index(e.postId(), e.doc()); // 업서트
    }

    @Async("esEventsExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(PostDeletedEvent e) {
        postIndexService.deleteById(e.postId());
    }
}
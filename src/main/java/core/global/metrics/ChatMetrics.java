package core.global.metrics;

import core.domain.chat.repository.ChatMessageRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class ChatMetrics {

    private final MeterRegistry registry;

    private final Timer messageDeliveryTimer;

    private final ChatMessageRepository chatMessageRepository;

    public ChatMetrics(MeterRegistry registry, ChatMessageRepository chatMessageRepository) {
        this.registry = registry;
        this.messageDeliveryTimer = Timer.builder("chat_message_delivery_seconds")
                .description("채팅 메시지 전달 지연 시간 (서버 인입 ~ 전송 완료)")
                .publishPercentileHistogram()
                .register(registry);
        this.chatMessageRepository = chatMessageRepository;
    }

    public void onMessageSent(String kind, boolean success) {
        Counter.builder("chat_messages_total")
                .tag("kind", kind)              // room / dm / system 등
                .tag("result", success ? "success" : "fail")
                .register(registry)
                .increment();
    }


    public <T> T recordDelivery(Runnable runnable) {
        messageDeliveryTimer.record(runnable);
        return null;
    }

    public void recordDelivery(long startMillis) {
        long dur = System.currentTimeMillis() - startMillis;
        messageDeliveryTimer.record(dur, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void onWsConnect(String reason) {
        Counter.builder("chat_ws_connections_total")
                .tag("event", "connect")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public void onWsDisconnect(String reason) {
        Counter.builder("chat_ws_connections_total")
                .tag("event", "disconnect")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    private double countActiveSenders() {
        long count = chatMessageRepository.countSendMessageUsersLast1Day();
        return (double) count;
    }


}

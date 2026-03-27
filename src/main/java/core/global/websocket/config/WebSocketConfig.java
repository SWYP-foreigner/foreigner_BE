package core.global.websocket.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

@Configuration
@EnableWebSocketMessageBroker
@Slf4j
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    // STOMP 채널 인터셉터 (인증 / 로깅 등)
    private StompChannelInterceptor stompChannelInterceptor;

    @Autowired
    @Lazy
    public void setStompChannelInterceptor(StompChannelInterceptor stompChannelInterceptor) {
        this.stompChannelInterceptor = stompChannelInterceptor;
    }

    /**
     * ============================
     * 1. 클라이언트 → 서버 (Inbound)
     * ============================
     *
     * - 클라이언트가 보내는 메시지 처리
     * - @MessageMapping 으로 들어오는 요청 처리
     * - 여기 막히면 SEND 자체가 늦어짐
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration
                .interceptors(stompChannelInterceptor)
                .taskExecutor()
                .corePoolSize(50)
                .maxPoolSize(200)
                .queueCapacity(1000);
    }

    /**
     * ============================
     * 2. 서버 → 클라이언트 (Outbound)
     * ============================
     *
     * - SimpMessagingTemplate.convertAndSend() 여기로 감
     *
     * queue가 꽉 차야 thread가 늘어남
     */
    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration
                .taskExecutor()
                .corePoolSize(50)
                .maxPoolSize(200)
                .queueCapacity(1000); // 🔥 이거 없으면 thread 절대 안 늘어남
    }

    /**
     * ============================
     * 3. WebSocket endpoint
     * ============================
     *
     * ws://.../ws 로 연결
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .addInterceptors(new HttpSessionHandshakeInterceptor());
    }

    /**
     * ============================
     * 4. 메시지 브로커 설정
     * ============================
     *
     * /topic → 구독 (SUBSCRIBE)
     * /app   → 서버 처리 (SEND)
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {

        // SimpleBroker (메모리 기반, 현재 사용 중)
        config.enableSimpleBroker("/topic");

        // 클라이언트 → 서버 prefix
        config.setApplicationDestinationPrefixes("/app");
    }
}
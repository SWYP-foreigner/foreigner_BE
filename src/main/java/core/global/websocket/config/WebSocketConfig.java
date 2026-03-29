package core.global.websocket.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
// import org.springframework.context.ApplicationListener; // 1. 리스너 제거
// import org.springframework.context.event.ContextRefreshedEvent; // 2. 이벤트 제거
import org.springframework.context.annotation.Configuration;
// import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolver; // 3. 리졸버 제거
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
// import org.springframework.messaging.simp.annotation.support.SimpAnnotationMethodMessageHandler; // 4. 핸들러 제거
// import org.springframework.security.messaging.context.AuthenticationPrincipalArgumentResolver; // 5. 보안 리졸버 제거
// import org.springframework.security.messaging.context.SecurityContextChannelInterceptor; // 6. 보안 인터셉터 제거
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;
import org.springframework.context.annotation.Lazy;

// import java.util.ArrayList; // 7. List 제거
// import java.util.List; // 8. List 제거

@Configuration
@EnableWebSocketMessageBroker
@Slf4j
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer { // 9. ApplicationListener 구현 제거

    private StompChannelInterceptor stompChannelInterceptor;


    @Autowired
    @Lazy
    public void setStompChannelInterceptor(StompChannelInterceptor stompChannelInterceptor) {
        this.stompChannelInterceptor = stompChannelInterceptor;
    }



    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .addInterceptors(new HttpSessionHandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(
                            org.springframework.http.server.ServerHttpRequest request,
                            org.springframework.http.server.ServerHttpResponse response,
                            org.springframework.web.socket.WebSocketHandler wsHandler,
                            java.util.Map<String, Object> attributes) throws Exception {
                        return super.beforeHandshake(request, response, wsHandler, attributes);
                    }
                });
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // (이하 코드는 동일)
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
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
                .queueCapacity(100);
    }

    /**
     * ============================
     * 2. 서버 → 클라이언트 (Outbound)
     * ============================
     *
     * - SimpMessagingTemplate.convertAndSend() 여기로 감
     * - 지금 네 병목이 여기 있음
     *
     * 핵심:
     * queue가 꽉 차야 thread가 늘어남
     */
    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration
                .taskExecutor()
                .corePoolSize(50)
                .maxPoolSize(200)
                .queueCapacity(100);
    }
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(64 * 1024);      // 메시지 크기 제한 (64KB)
        registration.setSendTimeLimit(20 * 1000);         // 전송 제한 시간 (20초)
        registration.setSendBufferSizeLimit(10 * 1024 * 1024); // 전송 버퍼 크기 (10MB로 확장!) 🔥
    }
}
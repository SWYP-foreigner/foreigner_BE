package core.global.websocket.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolver;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.annotation.support.SimpAnnotationMethodMessageHandler;
import org.springframework.security.messaging.context.AuthenticationPrincipalArgumentResolver;
import org.springframework.security.messaging.context.SecurityContextChannelInterceptor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;
// import org.springframework.context.annotation.Bean; // <-- 1. @Bean import가 제거됩니다.
import org.springframework.context.annotation.Lazy;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableWebSocketMessageBroker
@Slf4j
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer,
        ApplicationListener<ContextRefreshedEvent> {

    private StompChannelInterceptor stompChannelInterceptor;

    private final AuthenticationPrincipalArgumentResolver authenticationPrincipalResolver;
    private final SecurityContextChannelInterceptor securityContextChannelInterceptor;

    public WebSocketConfig(AuthenticationPrincipalArgumentResolver authenticationPrincipalResolver,
                           SecurityContextChannelInterceptor securityContextChannelInterceptor) {
        this.authenticationPrincipalResolver = authenticationPrincipalResolver;
        this.securityContextChannelInterceptor = securityContextChannelInterceptor;
    }


    @Autowired
    @Lazy
    public void setStompChannelInterceptor(StompChannelInterceptor stompChannelInterceptor) {
        this.stompChannelInterceptor = stompChannelInterceptor;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        log.info("Context refreshed. Manually adding ArgumentResolver...");
        SimpAnnotationMethodMessageHandler handler =
                event.getApplicationContext().getBean(SimpAnnotationMethodMessageHandler.class);

        List<HandlerMethodArgumentResolver> existingResolvers = handler.getArgumentResolvers();
        List<HandlerMethodArgumentResolver> newResolvers = new ArrayList<>();
        newResolvers.add(this.authenticationPrincipalResolver);
        newResolvers.addAll(existingResolvers);
        handler.setArgumentResolvers(newResolvers);
        log.info("AuthenticationPrincipalArgumentResolver added manually after context refresh.");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompChannelInterceptor);

        // 5. @Bean 메소드를 직접 호출하는 대신, 생성자에서 주입받은 필드를 사용합니다.
        registration.interceptors(this.securityContextChannelInterceptor);
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
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }
}
package core.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolver;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.annotation.support.SimpAnnotationMethodMessageHandler;
import org.springframework.security.messaging.context.AuthenticationPrincipalArgumentResolver;
import org.springframework.security.messaging.context.SecurityContextChannelInterceptor; // 👈 1. Import 추가
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;
import org.springframework.context.annotation.Bean;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableWebSocketMessageBroker
@Slf4j
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompChannelInterceptor stompChannelInterceptor;

    @Autowired
    private SimpAnnotationMethodMessageHandler simpAnnotationMethodMessageHandler;

    @Bean
    public AuthenticationPrincipalArgumentResolver authenticationPrincipalArgumentResolver() {
        return new AuthenticationPrincipalArgumentResolver();
    }

    // ⭐️ [추가 1]
    // SecurityContextHolder를 채워줄 인터셉터를 Bean으로 등록합니다.
    @Bean
    public SecurityContextChannelInterceptor securityContextChannelInterceptor() {
        return new SecurityContextChannelInterceptor();
    }

    public WebSocketConfig(StompChannelInterceptor stompChannelInterceptor) {
        this.stompChannelInterceptor = stompChannelInterceptor;
    }

    @PostConstruct
    public void addArgumentResolver() {
        log.info("Manually adding AuthenticationPrincipalArgumentResolver to SimpAnnotationMethodMessageHandler...");
        List<HandlerMethodArgumentResolver> existingResolvers =
                simpAnnotationMethodMessageHandler.getArgumentResolvers();
        List<HandlerMethodArgumentResolver> newResolvers = new ArrayList<>();
        newResolvers.add(authenticationPrincipalArgumentResolver());
        newResolvers.addAll(existingResolvers);
        simpAnnotationMethodMessageHandler.setArgumentResolvers(newResolvers);
        log.info("AuthenticationPrincipalArgumentResolver added manually.");
    }

    // ⭐️ [추가 2]
    // 채널에 두 개의 인터셉터를 모두 등록합니다.
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // 1. (CONNECT 시 실행) JWT 인증을 수행하고 세션에 userAuth를 저장합니다.
        registration.interceptors(stompChannelInterceptor);
        // 2. (모든 메시지 실행) 세션의 userAuth를 SecurityContextHolder로 복사합니다.
        registration.interceptors(securityContextChannelInterceptor());
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
package core.global.websocket.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolver;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.security.messaging.context.AuthenticationPrincipalArgumentResolver;
import org.springframework.security.messaging.context.SecurityContextChannelInterceptor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;
import org.springframework.context.annotation.Lazy;


import java.util.List;

@Configuration
@EnableWebSocketMessageBroker
@Slf4j
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

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

    /**
     * @AuthenticationPrincipal을 처리하는 ArgumentResolver를 표준 방식으로 등록합니다.
     */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
        argumentResolvers.add(authenticationPrincipalResolver);
        log.info("AuthenticationPrincipalArgumentResolver added via addArgumentResolvers.");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompChannelInterceptor);
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
package core.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired; // 👈 Import
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolver;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.annotation.support.SimpAnnotationMethodMessageHandler; // 👈 Import
import org.springframework.security.messaging.context.AuthenticationPrincipalArgumentResolver;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;
import org.springframework.context.annotation.Bean;
import jakarta.annotation.PostConstruct; // 👈 Import (Spring Boot 3)
// import javax.annotation.PostConstruct; // 👈 (Spring Boot 2)
import java.util.ArrayList; // 👈 Import
import java.util.List; // 👈 Import

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

    public WebSocketConfig(StompChannelInterceptor stompChannelInterceptor) {
        this.stompChannelInterceptor = stompChannelInterceptor;
    }

    /**
     * ⭐️ [변경 2: 핵심]
     * @PostConstruct를 이용해, Spring이 Bean을 모두 만든 후
     * SimpAnnotationMethodMessageHandler에 수동으로 Resolver를 등록합니다.
     */
    @PostConstruct
    public void addArgumentResolver() {
        log.info("Manually adding AuthenticationPrincipalArgumentResolver to SimpAnnotationMethodMessageHandler...");

        List<HandlerMethodArgumentResolver> existingResolvers =
                simpAnnotationMethodMessageHandler.getArgumentResolvers();

        List<HandlerMethodArgumentResolver> newResolvers = new ArrayList<>();
        // ⭐️ @Bean으로 만든 메소드를 호출하여 주입
        newResolvers.add(authenticationPrincipalArgumentResolver());
        newResolvers.addAll(existingResolvers);

        simpAnnotationMethodMessageHandler.setArgumentResolvers(newResolvers);
        log.info("AuthenticationPrincipalArgumentResolver added manually.");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompChannelInterceptor);
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
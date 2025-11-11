package core.global.config;

import core.global.constants.AIOnlyPaths;
import core.global.constants.AdminOnlyPaths;
import core.global.constants.UserOnlyPaths;
import core.global.metrics.ActiveUserRecordFilter;
import core.global.metrics.PresenceActivityFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.socket.EnableWebSocketSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@RequiredArgsConstructor
@Slf4j
@EnableWebSecurity
//@EnableWebSocketSecurity
public class SecurityConfig {

    private final JwtTokenFilter jwtTokenFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final ActiveUserRecordFilter activeUserRecordFilter;
    private final PresenceActivityFilter presenceActivityFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin/**").authenticated()
                        .requestMatchers(
                                "/api/v1/member/refresh",
                                "/api/v1/images/presign",
                                "/api/v1/member/google/app-login",
                                "/api/v1/member/apple/app-login",
                                "/api/v1/member/apple/**",
                                "/api/v1/member/profile/**",
                                "/api/v1/mypage/profile/**",
                                "/api/v1/board/*",
                                "/api/v1/users/**",
                                "/error",
                                "/api/v1/mypage/profile/find",
                                "/api/v1/mypage/**",
                                "/error/**",
                                "/api/v1/member/doLogin",
                                "/api/v1/member/verify-code",
                                "/api/v1/member/signup",
                                "/api/v1/member/send-verification-email",
                                "/api/v1/member/password/**",
                                "/api/v1/member/email/check",
                                "/actuator/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/swagger-resources/**",
                                "/swagger-ui.html",
                                "/ws/**",
                                "/ws"

                        ).permitAll()
                        // 2) ADMIN 전용
                        // hasRole("ADMIN")은 내부적으로 "ROLE_ADMIN" 권한을 찾습니다.
                        .requestMatchers(AdminOnlyPaths.PATTERNS.toArray(String[]::new)).hasRole("ADMIN")

                        // 3) AI 전용
                        .requestMatchers(AIOnlyPaths.PATTERNS.toArray(String[]::new)).hasRole("AI")

                        // 4) USER 전용(ADMIN도 접근 가능하도록 할지 선택)
                        .requestMatchers(UserOnlyPaths.PATTERNS.toArray(String[]::new)).hasAnyRole("USER", "ADMIN","AI")

                        // 5) 그 밖의 모든 요청은 인증 필요
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                );
        http.addFilterBefore(jwtTokenFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(activeUserRecordFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(presenceActivityFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
   /* @Bean
    public AuthorizationManager<Message<?>> messageAuthorizationManager(MessageMatcherDelegatingAuthorizationManager.Builder messages) {
        messages
                .simpTypeMatchers(SimpMessageType.DISCONNECT).permitAll()
                .simpDestMatchers("/app/**").authenticated()
                .anyMessage().authenticated();

        return messages.build();
    }*/
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOriginPatterns(List.of("*"));
        c.setAllowCredentials(false);
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", c);
        return src;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

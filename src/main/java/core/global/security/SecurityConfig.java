package core.global.security;

import core.global.constants.*;
import core.global.metrics.ActiveUserRecordFilter;
import core.global.metrics.PresenceActivityFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
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
                /*todo
                    완료 추후에 클라이언트에서 변경되고 나서 user와 visitor 경로 권한 분리 후 429->403으로 내려주게 변경

                *   */
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth

                        // 1) [수정] 완전 공개 경로 (JWT 불필요)
                        // PermitAllPaths에 정의된 경로들
                        .requestMatchers(PermitAllPaths.PATTERNS.toArray(String[]::new)).permitAll()

                        // 2) ADMIN 전용 경로
                        .requestMatchers(AdminOnlyPaths.PATTERNS.toArray(String[]::new)).hasRole("ADMIN")

                        // 3) AI 전용 경로 (ADMIN도 접근 가능)
                        .requestMatchers(AIOnlyPaths.PATTERNS.toArray(String[]::new)).hasAnyRole("AI", "ADMIN")

                        // 4) [핵심] 나머지 모든 경로 (모든 일반 기능)
                        // VISITOR, USER, ADMIN 모두 접근 가능하도록 설정
                        // (클라이언트가 준비될 때까지 VISITOR와 USER를 동일하게 취급)
                        .anyRequest().hasAnyRole("VISITOR", "USER", "ADMIN")
                )
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                );

        http.addFilterBefore(jwtTokenFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(activeUserRecordFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(presenceActivityFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
    /* todo chat 리팩토링 끝날시 다시 open   */
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

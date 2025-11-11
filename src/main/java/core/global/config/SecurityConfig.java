package core.global.config;

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
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 1) 완전 공개
                        .requestMatchers(PermitAllPaths.PATTERNS.toArray(String[]::new)).permitAll()

                        // 2) ADMIN 전용
                        .requestMatchers(AdminOnlyPaths.PATTERNS.toArray(String[]::new)).hasRole("ADMIN")

                        // 3) USER 전용(ADMIN 포함)
                        .requestMatchers(UserOnlyPaths.PATTERNS.toArray(String[]::new)).hasAnyRole("USER","ADMIN")

                        // 4) (선택) 인증만 필요
                        .requestMatchers(AIOnlyPaths.PATTERNS.toArray(String[]::new)).authenticated()

                        // 5) 나머지
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

package core.global.config;

import core.global.constants.AdminOnlyPaths;
import core.global.constants.UserOnlyPaths;
import core.global.constants.VisitorOnlyPaths;
import core.global.handler.VisitorUserGuardAccessDeniedHandler;
import core.global.metrics.ActiveUserRecordFilter;
import core.global.metrics.PresenceActivityFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
public class SecurityConfig {

    private final JwtTokenFilter jwtTokenFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final ActiveUserRecordFilter activeUserRecordFilter;
    private final PresenceActivityFilter presenceActivityFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        log.info("jwtTokenFilter = {}", jwtTokenFilter);

        http
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // (1) 관리자
                        .requestMatchers(AdminOnlyPaths.PATTERNS.toArray(String[]::new)).hasRole("ADMIN")

                        // 로그인 안해도 허용
                        .requestMatchers(
                                "/api/v1/member/google/app-login",
                                "/api/v1/member/apple/app-login",
                                "/api/v1/member/doLogin",
                                "/api/v1/member/signup",
                                "/api/v1/member/verify-code",
                                "/api/v1/member/send-verification-email",
                                "/api/v1/member/password/**",
                                "/api/v1/member/email/check",
                                "/api/v1/member/refresh",
                                "/api/v1/images/presign",

                                "/actuator/**",
                                "/error",
                                "/error/**",
                                "/ws/**",
                                "/ws"
                        )
                        .permitAll()

                        .requestMatchers(HttpMethod.GET, "/api/v1/boards/*/posts")
                        .hasAnyRole("VISITOR","USER","ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/boards/*/posts")
                        .hasAnyRole("USER","ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/boards/*/posts/**")
                        .hasAnyRole("USER","ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/boards/*/posts/**")
                        .hasAnyRole("USER","ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/boards/*/posts/**")
                        .hasAnyRole("USER","ADMIN")

                        // (2) 비지터 전용
                        .requestMatchers(VisitorOnlyPaths.PATTERNS.toArray(String[]::new)).hasRole("VISITOR")

                        // (3) 유저 전용
                        .requestMatchers(UserOnlyPaths.PATTERNS.toArray(String[]::new)).hasAnyRole("USER", "ADMIN")

                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(new VisitorUserGuardAccessDeniedHandler(UserOnlyPaths.PATTERNS))
                );
        http.addFilterBefore(jwtTokenFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(activeUserRecordFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(presenceActivityFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

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

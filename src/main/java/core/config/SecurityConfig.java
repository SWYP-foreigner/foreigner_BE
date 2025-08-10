package core.config;


import com.foreigner.core.common.JwtTokenFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenFilter jwtTokenFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                // 모바일 OAuth2(PKCE) 전용 공개 엔드포인트
                                "/auth/google/callback",
                                "/auth/google/exchange",

                                // 기존 공개 경로 (필요 시 유지/조정)
                                "/member/create", "/member/doLogin",
                                "/member/google/doLogin", "/member/kakao/doLogin",
                                "/oauth2/**",
                                "/swagger-ui/**", "/v3/api-docs/**", "/actuator/health"
                        ).permitAll()
                        .anyRequest().authenticated()
                );

        // JWT 필터는 UsernamePasswordAuthenticationFilter 이전에
        http.addFilterBefore(jwtTokenFilter, UsernamePasswordAuthenticationFilter.class);

        // (웹 리다이렉트 방식 안 쓸 거면 oauth2Login 설정 제거)
        // .oauth2Login(o -> o.successHandler(googleOauth2LoginSuccess))

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration c = new CorsConfiguration();

        // --- 운영에서는 정확한 도메인으로 교체하세요 ---
        // 예) c.setAllowedOrigins(List.of("https://yourapp.example.com", "https://app.yourapp.com"));
        // 개발 편의를 위해 패턴 허용 + credentials 미사용으로 설정
        c.setAllowedOriginPatterns(List.of("*")); // dev 편의. 운영은 setAllowedOrigins로 명시 권장
        c.setAllowCredentials(false);            // Authorization 헤더 사용 시 보통 false 권장
        c.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        c.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", c);
        return src;
    }
}

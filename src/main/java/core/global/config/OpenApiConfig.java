package core.global.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@OpenAPIDefinition(
        info = @Info(title = "API 문서", version = "v1")
)
public class OpenApiConfig {

    @Value("${swagger.server-url}")
    private String serverUrl;

    @Bean
    public OpenAPI openAPI() {
        // 서버 설정
        Server httpsServer = new Server();
        httpsServer.setUrl(serverUrl);

        // SecurityScheme 설정 (JWT Bearer Token)
        SecurityScheme securityScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)   // HTTP 인증 방식
                .scheme("bearer")                // Bearer 방식
                .bearerFormat("JWT")             // JWT 사용
                .in(SecurityScheme.In.HEADER)    // Authorization 헤더에 넣음
                .name("Authorization");          // 헤더 키 이름

        // SecurityRequirement 설정
        SecurityRequirement securityRequirement = new SecurityRequirement()
                .addList("BearerAuth");

        // OpenAPI 객체 반환
        return new OpenAPI()
                .servers(List.of(httpsServer))
                .addSecurityItem(securityRequirement)
                .components(new io.swagger.v3.oas.models.Components()
                        .addSecuritySchemes("BearerAuth", securityScheme));
    }
}

package core.global.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.Components;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@OpenAPIDefinition(info = @Info(title = "API 문서", version = "v1"))
@SecurityScheme(
        name = "bearerAuth",            // 스키마 이름
        type = SecuritySchemeType.HTTP, // HTTP 인증
        scheme = "bearer",              // Bearer 방식
        bearerFormat = "JWT",           // UI 힌트
        in = SecuritySchemeIn.HEADER    // Authorization 헤더 사용
)
public class OpenApiConfig {

    @Value("${swagger.server-url}")
    private String serverUrl;

    @Bean
    public OpenAPI openAPI() {
        Server httpsServer = new Server().url(serverUrl);

        // 전역 Security 요구사항 추가 (모든 API에 기본 적용)
        SecurityRequirement securityItem = new SecurityRequirement().addList("bearerAuth");

        return new OpenAPI()
                .servers(List.of(httpsServer))
                .components(new Components().addSecuritySchemes(
                        "bearerAuth",
                        new io.swagger.v3.oas.models.security.SecurityScheme()
                                .type(io.swagger.v3.oas.models.security.SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                ))
                .addSecurityItem(securityItem);
    }
}

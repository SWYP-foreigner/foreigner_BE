package core.global.docs;

import core.global.docs.annotations.*;
import core.global.enums.errorcode.*;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springdoc.core.customizers.OpenApiCustomizer;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

@Configuration
@OpenAPIDefinition(
        info = @Info(title = "API 문서", version = "v1")
)
public class OpenApiConfig {

    @Value("${swagger.server-url}")
    private String serverUrl;

    @Bean
    public OpenAPI openAPI() {
        Server httpsServer = new Server();
        httpsServer.setUrl(serverUrl);
        return new OpenAPI()
                .servers(List.of(httpsServer));
    }

    @Bean
    public OpenApiCustomizer errorCodeAppender() {
        return openApi -> {

            if (openApi.getPaths() == null) {
                return;
            }

            openApi.getPaths().forEach((path, pathItem) -> {

                pathItem.readOperations().forEach(operation -> {

                    if (operation.getExtensions() == null) {
                        return;
                    }

                    Object methodObj = operation.getExtensions().get("x-spring-method");
                    Method method = resolveMethod(methodObj);

                    // Method를 찾지 못하면 에러코드 추가 중단
                    if (method == null) {
                        return;
                    }

                    // 🔥 AuthErrorDocs
                    AuthErrorDocs authDocs = method.getAnnotation(AuthErrorDocs.class);
                    if (authDocs != null) {
                        for (AuthErrorCode code : authDocs.value()) {
                            addApiError(operation, code.httpStatus().value(), code.message());
                        }
                    }

                    // 🔥 UserErrorDocs
                    UserErrorDocs userDocs = method.getAnnotation(UserErrorDocs.class);
                    if (userDocs != null) {
                        for (UserErrorCode code : userDocs.value()) {
                            addApiError(operation, code.httpStatus().value(), code.message());
                        }
                    }

                    // 🔥 CommunityErrorDocs
                    CommunityErrorDocs commDocs = method.getAnnotation(CommunityErrorDocs.class);
                    if (commDocs != null) {
                        for (CommunityErrorCode code : commDocs.value()) {
                            addApiError(operation, code.httpStatus().value(), code.message());
                        }
                    }

                    // 🔥 CommonErrorCodeDocs
                    CommonErrorCodeDocs commonDocs = method.getAnnotation(CommonErrorCodeDocs.class);
                    if (commonDocs != null) {
                        for (CommonErrorCode code : commonDocs.value()) {
                            addApiError(operation, code.httpStatus().value(), code.message());
                        }
                    }

                    // 🔥 ImageErrorCodeDocs
                    ImageErrorCodeDocs imageDocs = method.getAnnotation(ImageErrorCodeDocs.class);
                    if (imageDocs != null) {
                        for (ImageErrorCode code : imageDocs.value()) {
                            addApiError(operation, code.httpStatus().value(), code.message());
                        }
                    }

                    // 🔥 ChatErrorDocs
                    ChatErrorDocs chatDocs = method.getAnnotation(ChatErrorDocs.class);
                    if (chatDocs != null) {
                        for (ChatErrorCode code : chatDocs.value()) {
                            addApiError(operation, code.httpStatus().value(), code.message());
                        }
                    }

                });
            });
        };
    }

    /**
     * swagger 확장정보(Map<class, method>) → Java Method 객체 복원
     */
    private Method resolveMethod(Object methodObj) {
        try {
            if (methodObj instanceof Map<?, ?> info) {

                String className = (String) info.get("class");
                String methodName = (String) info.get("method");

                Class<?> clazz = Class.forName(className);

                // 파라미터 타입 정보를 활용하면 정확도 상승
                Object paramsObj = info.get("parameters");
                Method[] methods = clazz.getDeclaredMethods();

                if (paramsObj instanceof List<?> paramTypeNames && !paramTypeNames.isEmpty()) {
                    for (Method m : methods) {
                        if (!m.getName().equals(methodName)) continue;

                        Class<?>[] methodParamTypes = m.getParameterTypes();

                        if (methodParamTypes.length != paramTypeNames.size()) continue;

                        boolean match = true;
                        for (int i = 0; i < methodParamTypes.length; i++) {
                            if (!methodParamTypes[i].getName().equals(paramTypeNames.get(i))) {
                                match = false;
                                break;
                            }
                        }

                        if (match) return m;
                    }
                }

                // 파라미터 정보 없는 경우 이름만으로 매칭
                for (Method m : methods) {
                    if (m.getName().equals(methodName)) {
                        return m;
                    }
                }
            }

        } catch (Exception e) {
            return null;
        }

        return null;
    }

    private void addApiError(Operation operation, int status, String description) {

        if (operation.getResponses() == null) {
            operation.setResponses(new io.swagger.v3.oas.models.responses.ApiResponses());
        }

        operation.getResponses().addApiResponse(
                String.valueOf(status),
                new io.swagger.v3.oas.models.responses.ApiResponse().description(description)
        );
    }

}
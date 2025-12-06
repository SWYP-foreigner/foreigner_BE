package core.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.*;

@Component
public class SpringMethodOperationMapper implements OpenApiCustomizer {

    private final RequestMappingHandlerMapping handlerMapping;

    public SpringMethodOperationMapper(
            @Qualifier("requestMappingHandlerMapping")
            RequestMappingHandlerMapping handlerMapping
    ) {
        this.handlerMapping = handlerMapping;
    }

    @Override
    public void customise(OpenAPI openApi) {

        Map<String, Map<HttpMethod, Method>> methodMap = new HashMap<>();

        // SPRING HANDLERS
        handlerMapping.getHandlerMethods().forEach((info, handlerMethod) -> {

            Set<String> patterns = new LinkedHashSet<>();

            if (info.getPathPatternsCondition() != null) {
                info.getPathPatternsCondition().getPatterns()
                        .forEach(p -> patterns.add(p.getPatternString()));
            }

            if (patterns.isEmpty() && info.getPatternsCondition() != null) {
                patterns.addAll(info.getPatternsCondition().getPatterns());
            }

            var methods = info.getMethodsCondition().getMethods();

            for (String pattern : patterns) {

                String springPath = normalize(pattern);

                for (RequestMethod m : methods) {

                    methodMap.computeIfAbsent(springPath, k -> new HashMap<>())
                            .put(HttpMethod.valueOf(m.name()), handlerMethod.getMethod());
                }
            }
        });

        // SWAGGER OPERATIONS
        openApi.getPaths().forEach((swaggerPath, pathItem) -> {

            String swaggerNorm = normalize(swaggerPath);

            pathItem.readOperationsMap().forEach((swaggerMethod, operation) -> {

                HttpMethod springHttpMethod = toSpringHttpMethod(swaggerMethod);
                Method method = methodMap
                        .getOrDefault(swaggerNorm, Map.of())
                        .get(springHttpMethod);

                if (method != null) {
                    Map<String, Object> info = new HashMap<>();
                    info.put("class", method.getDeclaringClass().getName());
                    info.put("method", method.getName());
                    info.put("parameters", Arrays.stream(method.getParameterTypes())
                            .map(Class::getName)
                            .toList());
                    info.put("returnType", method.getReturnType().getName());

                    operation.addExtension("x-spring-method", info);

                }
            });
        });
    }

    private String normalize(String path) {
        return path.replaceAll("\\{([^/]+?)\\}", "{$1}");
    }

    private HttpMethod toSpringHttpMethod(PathItem.HttpMethod swaggerMethod) {
        try {
            return HttpMethod.valueOf(swaggerMethod.name());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}

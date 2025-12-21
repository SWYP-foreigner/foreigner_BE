package core.global.smoke.runner;

import core.global.smoke.utils.SmokeProperties;
import core.global.smoke.dto.SmokeItem;
import core.global.smoke.dto.SmokeResult;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class SmokeGateRunner {

    private final SmokeProperties props;
    private final WebClient client;

    public SmokeGateRunner(SmokeProperties props, WebClient.Builder builder) {
        this.props = props;
        this.client = builder.baseUrl(props.getBaseUrl()).build();
    }

    public SmokeResult run(String mode) {
        long started = System.currentTimeMillis();
        String accessToken = null;

        if ("full".equalsIgnoreCase(mode)) {
            try {
                accessToken = fetchAdminToken();
            } catch (Exception e) {
                return createLoginFailureResult(mode, started, e);
            }
        }

        List<SmokeProperties.Case> cases = "full".equalsIgnoreCase(mode)
                ? props.getFull()
                : props.getGate();

        if (cases == null) cases = List.of();

        // 성공/실패 리스트 분리
        List<SmokeItem> passedItems = new ArrayList<>();
        List<SmokeItem> failedItems = new ArrayList<>();

        int passed = 0;
        int failed = 0;
        Duration perRequestTimeout = Duration.ofSeconds(3);

        for (var c : cases) {
            long s = System.currentTimeMillis();
            try {
                String targetUrl = c.getType().equalsIgnoreCase("EXTERNAL")
                        ? c.getPath()
                        : props.getBaseUrl() + c.getPath();

                var requestSpec = client.method(HttpMethod.valueOf(c.getMethod().toUpperCase()))
                        .uri(targetUrl);

                if (accessToken != null && !c.getType().equalsIgnoreCase("EXTERNAL") && !c.getType().equalsIgnoreCase("HEALTH")) {
                    requestSpec.header("Authorization", "Bearer " + accessToken);
                }

                int status = requestSpec
                        .exchangeToMono(resp -> resp.releaseBody().thenReturn(resp.statusCode().value()))
                        .timeout(perRequestTimeout)
                        .block();

                boolean ok = (status == c.getExpectedStatus());

                SmokeItem item = new SmokeItem(
                        c.getName(), c.getMethod(), c.getPath(),
                        status, System.currentTimeMillis() - s, ok, null
                );

                if (ok) {
                    passed++;
                    passedItems.add(item);
                } else {
                    failed++;
                    failedItems.add(item);
                }
            } catch (Exception e) {
                failed++;
                failedItems.add(new SmokeItem(
                        c.getName(), c.getMethod(), c.getPath(),
                        null, System.currentTimeMillis() - s, false,
                        e.getClass().getSimpleName() + ": " + safeMsg(e.getMessage())
                ));
            }
        }

        return new SmokeResult(
                mode,
                (failed == 0),
                cases.size(),
                passed,
                failed,
                System.currentTimeMillis() - started,
                passedItems,
                failedItems
        );
    }

    // 관리자 토큰 발급 로직
    private String fetchAdminToken() {
        // 로그인 API 응답 구조에 맞는 DTO 필요 (예: LoginResponse.accessToken)
        Map<String, String> loginReq = Map.of(
                "email", props.getAdmin().getEmail(),
                "password", props.getAdmin().getPassword()
        );

        return client.post()
                .uri(props.getAdmin().getLoginPath())
                .bodyValue(loginReq)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(res -> {
                    // JSON 응답 구조에 따라 파싱 (예: data.accessToken)
                    Map<String, Object> data = (Map<String, Object>) res.get("data");
                    return (String) data.get("accessToken");
                })
                .block(Duration.ofSeconds(5));
    }

    private SmokeResult createLoginFailureResult(String mode, long started, Exception e) {
        SmokeItem loginError = new SmokeItem(
                "ADMIN_LOGIN", "POST", props.getAdmin().getLoginPath(),
                null, 0, false, "Login Failed: " + safeMsg(e.getMessage())
        );

        return new SmokeResult(
                mode, false, 0, 0, 1,
                System.currentTimeMillis() - started,
                List.of(),           // passedItems (빈 리스트)
                List.of(loginError)  // failedItems (로그인 에러 항목 포함)
        );
    }

    private String safeMsg(String msg) {
        if (msg == null) return "";
        // 로그/응답에 길게 새지 않게 컷
        return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
    }
}

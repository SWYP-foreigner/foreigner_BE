package core.global.smoke.runner;

import com.warrenstrange.googleauth.GoogleAuthenticator;
import core.global.smoke.dto.SmokeItem;
import core.global.smoke.dto.SmokeResult;
import core.global.smoke.utils.SmokeProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class SmokeGateRunner {

    private final SmokeProperties props;
    private final RestTemplate restTemplate;
    private final GoogleAuthenticator gAuth = new GoogleAuthenticator();

    @Value("${otp.admin-secret}")
    private String adminOtpSecret;

    public SmokeGateRunner(SmokeProperties props, RestTemplate restTemplate) {
        this.props = props;
        this.restTemplate = restTemplate;
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
            if ("LOGIN".equalsIgnoreCase(c.getType())) {
                continue;
            }

            long s = System.currentTimeMillis();
            try {
                String targetUrl = c.getType().equalsIgnoreCase("EXTERNAL")
                        ? c.getPath()
                        : props.getBaseUrl() + c.getPath();

                HttpHeaders headers = new HttpHeaders();
                if (accessToken != null && !c.getType().equalsIgnoreCase("EXTERNAL")) {
                    headers.setBearerAuth(accessToken);
                }
                if (c.getBody() != null) {
                    headers.setContentType(MediaType.APPLICATION_JSON);
                }

                HttpEntity<Object> requestEntity = new HttpEntity<>(c.getBody(), headers);

                // 3. RestTemplate 호출 (WebClient.exchange...block 대체)
                // c.getMethod()가 "GET", "POST" 등의 문자열로 들어오므로 HttpMethod로 변환
                ResponseEntity<String> response = restTemplate.exchange(
                        targetUrl,
                        HttpMethod.valueOf(c.getMethod().toUpperCase()),
                        requestEntity,
                        String.class
                );

                int status = response.getStatusCodeValue();
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
        int code = gAuth.getTotpPassword(adminOtpSecret);
        String otpCode = String.format("%06d", code);

        Map<String, String> loginReq = Map.of(
                "email", props.getAdmin().getEmail(),
                "password", props.getAdmin().getPassword(),
                "otpCode", otpCode
        );

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    props.getAdmin().getLoginPath(),
                    loginReq,
                    String.class
            );

            List<String> cookies = response.getHeaders().get("Set-Cookie");
            if (cookies == null || cookies.isEmpty()) {
                throw new RuntimeException("No Set-Cookie header found");
            }

            return cookies.stream()
                    .filter(cookie -> cookie.startsWith("accessToken="))
                    .map(cookie -> cookie.split(";")[0].split("=")[1])
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("accessToken cookie not found"));

        } catch (Exception e) {
            throw new RuntimeException("Admin Login Failed with Real OTP", e);
        }
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

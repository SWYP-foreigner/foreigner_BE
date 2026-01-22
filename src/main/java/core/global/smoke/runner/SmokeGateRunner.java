package core.global.smoke.runner;

import com.warrenstrange.googleauth.GoogleAuthenticator;
import core.global.smoke.dto.SmokeItem;
import core.global.smoke.dto.SmokeResult;
import core.global.smoke.utils.SmokeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
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

        log.info("===== Smoke Test 시작 (모드: {}) =====", mode);

        if ("full".equalsIgnoreCase(mode)) {
            try {
                accessToken = fetchAdminToken();
                log.info("관리자 인증 성공");
            } catch (Exception e) {
                log.error("관리자 인증 실패: {}", e.getMessage());
                return createLoginFailureResult(mode, started, e);
            }
        }

        List<SmokeProperties.Case> cases = "full".equalsIgnoreCase(mode)
                ? props.getFull()
                : props.getGate();

        if (cases == null) {
            log.warn("실행할 테스트 케이스가 없습니다.");
            cases = List.of();
        }

        List<SmokeItem> passedItems = new ArrayList<>();
        List<SmokeItem> failedItems = new ArrayList<>();

        int passed = 0;
        int failed = 0;

        for (var c : cases) {
            if ("LOGIN".equalsIgnoreCase(c.getType())) continue;

            long s = System.currentTimeMillis();
            String targetUrl = c.getType().equalsIgnoreCase("EXTERNAL")
                    ? c.getPath()
                    : props.getBaseUrl() + c.getPath();

            log.info("[테스트 중] {} -> {} {}", c.getName(), c.getMethod(), targetUrl);

            try {
                HttpHeaders headers = new HttpHeaders();

                if (c.getPath().contains("/ws") || c.getName().toLowerCase().contains("websocket")) {
                    headers.set("Upgrade", "websocket");
                    headers.set("Connection", "Upgrade");
                    headers.set("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw==");
                    headers.set("Sec-WebSocket-Version", "13");
                }

                if (accessToken != null && !c.getType().equalsIgnoreCase("EXTERNAL")) {
                    headers.setBearerAuth(accessToken);
                }
                if (c.getBody() != null) {
                    headers.setContentType(MediaType.APPLICATION_JSON);
                }

                HttpEntity<Object> requestEntity = new HttpEntity<>(c.getBody(), headers);

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
                    log.info("  >> [성공] 결과: {}, 소요시간: {}ms", status, (System.currentTimeMillis() - s));
                } else {
                    failed++;
                    failedItems.add(item);
                    log.warn("  >> [실패] 예상상태: {}, 실제상태: {}", c.getExpectedStatus(), status);
                }
            } catch (Exception e) {
                failed++;
                String errorMsg = e.getClass().getSimpleName() + ": " + safeMsg(e.getMessage());
                failedItems.add(new SmokeItem(
                        c.getName(), c.getMethod(), c.getPath(),
                        null, System.currentTimeMillis() - s, false, errorMsg
                ));
                log.error("  >> [예외 발생] 메시지: {}", errorMsg);
            }
        }

        log.info("===== Smoke Test 완료 (성공: {}, 실패: {}, 총 소요시간: {}ms) =====",
                passed, failed, (System.currentTimeMillis() - started));

        return new SmokeResult(
                mode, (failed == 0), cases.size(), passed, failed,
                System.currentTimeMillis() - started, passedItems, failedItems
        );
    }

    private String fetchAdminToken() {
        log.info("===== 관리자 2단계 인증 시작 =====");

        // [Step 1] ID/PW 로그인 시도
        String loginUrl = props.getBaseUrl() + props.getAdmin().getLoginPath();
        log.info("[Step 1] 로그인 시도 URL: {}", loginUrl);

        Map<String, String> step1Req = Map.of(
                "email", props.getAdmin().getEmail(),
                "password", props.getAdmin().getPassword()
        );

        // ApiResponse<AdminLoginStep1Response> 파싱
        ResponseEntity<Map> step1Response = restTemplate.postForEntity(loginUrl, step1Req, Map.class);
        Map<String, Object> step1Body = step1Response.getBody();

        if (step1Body == null || step1Body.get("data") == null) {
            throw new RuntimeException("Step 1 응답 데이터가 없습니다.");
        }

        // AdminLoginStep1Response DTO 내부의 tempToken 추출
        Map<String, Object> data1 = (Map<String, Object>) step1Body.get("data");
        String tempToken = (String) data1.get("tempToken");

        if (tempToken == null) {
            throw new RuntimeException("Step 1 실패: tempToken이 응답에 포함되지 않았습니다.");
        }
        log.info("[Step 1] 성공: tempToken 획득 완료");

        // [Step 2] OTP 검증 시도
        String verifyUrl = props.getBaseUrl() + "/api/v1/member/admin/otp-verify";
        log.info("[Step 2] OTP 검증 URL: {}", verifyUrl);

        // OtpVerificationRequest 필드 구성
        log.info("[Step 2] 매직 코드(000000)로 검증 시도");
        Map<String, String> step2Req = Map.of(
                "email", props.getAdmin().getEmail(),
                "tempToken", tempToken,
                "otpCode", "000000" // 서버 GoogleOtpService의 magicCodeEnabled 로직을 탐
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(step2Req, headers);

        ResponseEntity<Map> step2Response = restTemplate.postForEntity(verifyUrl, entity, Map.class);

        // Step 2는 쿠키에서 토큰을 추출 (Controller에서 HttpServletResponse에 쿠키 추가함)
        List<String> cookies = step2Response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (cookies == null || cookies.isEmpty()) {
            throw new RuntimeException("Step 2 실패: 응답 헤더에 Set-Cookie가 없습니다.");
        }

        String accessToken = cookies.stream()
                .filter(cookie -> cookie.startsWith("accessToken="))
                .map(cookie -> cookie.split(";")[0].split("=")[1])
                .findFirst()
                .orElseThrow(() -> new RuntimeException("accessToken 쿠키를 찾을 수 없습니다."));

        log.info("===== 관리자 2단계 인증 최종 성공 =====");
        return accessToken;
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

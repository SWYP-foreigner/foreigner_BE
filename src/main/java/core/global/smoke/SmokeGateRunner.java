package core.global.smoke;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class SmokeGateRunner {

    private final SmokeProperties props;
    private final WebClient client;

    public SmokeGateRunner(SmokeProperties props, WebClient.Builder builder) {
        this.props = props;
        this.client = builder.baseUrl(props.getBaseUrl()).build();
    }

    public SmokeResult runGate() {
        long started = System.currentTimeMillis();

        List<SmokeProperties.Case> cases = props.getGate() == null ? List.of() : props.getGate();
        List<SmokeItem> items = new ArrayList<>();

        int passed = 0;
        int failed = 0;

        Duration perRequestTimeout = Duration.ofSeconds(3);

        for (var c : cases) {
            long s = System.currentTimeMillis();
            try {
                int status = client.method(HttpMethod.valueOf(c.getMethod()))
                        .uri(c.getPath())
                        .exchangeToMono(resp -> resp.releaseBody().thenReturn(resp.statusCode().value()))
                        .timeout(perRequestTimeout)
                        .block();

                boolean ok = (status == c.getExpectedStatus());
                if (ok) passed++; else failed++;

                items.add(new SmokeItem(
                        c.getName(), c.getMethod(), c.getPath(),
                        status, System.currentTimeMillis() - s, ok, null
                ));
            } catch (Exception e) {
                failed++;
                items.add(new SmokeItem(
                        c.getName(), c.getMethod(), c.getPath(),
                        null, System.currentTimeMillis() - s, false,
                        e.getClass().getSimpleName() + ": " + safeMsg(e.getMessage())
                ));
            }
        }

        boolean ok = (failed == 0);
        return new SmokeResult(
                "gate",
                ok,
                cases.size(),
                passed,
                failed,
                System.currentTimeMillis() - started,
                items
        );
    }

    private String safeMsg(String msg) {
        if (msg == null) return "";
        // 로그/응답에 길게 새지 않게 컷
        return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
    }
}

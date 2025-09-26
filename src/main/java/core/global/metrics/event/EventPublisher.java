package core.global.metrics.event;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class EventPublisher {

    // yml의 app.search.es-url 그대로 사용
    @Value("${app.search.es-url}")
    private String esUrl;

    // 별칭은 파이프라인에서 이미 생성: events_write
    private static final String WRITE_ALIAS = "events_write";

    private final WebClient webClient = WebClient.builder().build();

    public Mono<Void> publish(UsageEvent e) {
        return webClient.post()
                .uri(esUrl + "/" + WRITE_ALIAS + "/_doc")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(e)
                .retrieve()
                .bodyToMono(Map.class)
                .then()
                // 전송 실패가 본 요청에 영향 주지 않도록 무시
                .onErrorResume(ex -> Mono.empty());
    }
}

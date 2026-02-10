package core.domain.aiuser.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiClientImpl implements AiClient {

    @Value("${openai.api-key}")
    private String apiKey;

    private final RestTemplate openaiRestTemplate;
    private static final String API_URL = "https://api.openai.com/v1/responses";

    @Override
    public String generateResponse(List<Map<String, Object>> messages) {
        int maxRetries = 3;
        long waitTime = 1000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(apiKey);

                Map<String, Object> body = new HashMap<>();
                body.put("model", "gpt-5.1-codex-mini");
                body.put("input", messages);
                body.put("max_output_tokens", 2000);

                HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

                ResponseEntity<Map> response = openaiRestTemplate.postForEntity(API_URL, request, Map.class);
                Map<String, Object> responseBody = response.getBody();

                if (responseBody != null && responseBody.containsKey("output")) {
                    List<Map<String, Object>> outputs = (List<Map<String, Object>>) responseBody.get("output");

                    for (Map<String, Object> out : outputs) {
                        if ("message".equals(out.get("type")) && out.containsKey("content")) {
                            List<Map<String, Object>> contents = (List<Map<String, Object>>) out.get("content");

                            for (Map<String, Object> c : contents) {
                                if ("output_text".equals(c.get("type"))) {
                                    return (String) c.get("text");
                                }
                            }
                        }
                    }
                }
                throw new RuntimeException("Unexpected response structure or empty content");

            } catch (Exception e) {
                log.warn("⚠️ OpenAI API 호출 실패 (시도 {}/{}): {}", attempt, maxRetries, e.getMessage());

                if (attempt == maxRetries) {
                    log.error("❌ 최종 API 호출 실패. 더 이상 재시도하지 않습니다.");
                    return null;
                }

                try {
                    Thread.sleep(waitTime);
                    waitTime *= 2;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("API 재시도 대기 중 인터럽트 발생");
                    return null;
                }
            }
        }

        return null;
    }
}
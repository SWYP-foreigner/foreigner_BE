package core.global.ai.client;

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

    private final RestTemplate restTemplate = new RestTemplate();
    // [수정] 엔드포인트를 v1/responses로 변경합니다.
    private static final String API_URL = "https://api.openai.com/v1/responses";

    @Override
    public String generateResponse(List<Map<String, Object>> messages) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> body = new HashMap<>();
            body.put("model", "gpt-5.1-codex-mini");

            // [참고] v1/responses API는 기존의 'messages' 리스트 형식을 수용하면서도
            // 내부적으로는 'instructions'와 'output' 구조를 가질 수 있습니다.
            body.put("messages", messages);

            body.put("temperature", 0.8);
            body.put("max_tokens", 150);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(API_URL, request, Map.class);

            Map<String, Object> responseBody = response.getBody();

            // [주의] v1/responses의 응답 구조는 기존과 다를 수 있습니다.
            // 보통 'output' 혹은 'choices'를 포함하지만, 에러 메시지에 따라 구조를 확인해야 합니다.
            if (responseBody != null && responseBody.containsKey("output")) {
                Map<String, Object> output = (Map<String, Object>) responseBody.get("output");
                return (String) output.get("content");
            }

            // 기존 choices 구조로도 시도 (하이브리드 대응)
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            return (String) message.get("content");

        } catch (Exception e) {
            log.error("OpenAI v1/responses 호출 오류: {}", e.getMessage());
            throw new RuntimeException("AI Service Unavailable with gpt-5.1-codex-mini");
        }
    }
}
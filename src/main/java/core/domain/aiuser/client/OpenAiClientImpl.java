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

    private final RestTemplate restTemplate = new RestTemplate();
    // [참고] 모델에 따라 엔드포인트가 다를 수 있으니 확인 필요 (표준은 v1/chat/completions 이지만, 사용하시는 v1/responses 구조 유지)
    private static final String API_URL = "https://api.openai.com/v1/responses";

    @Override
    public String generateResponse(List<Map<String, Object>> messages) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> body = new HashMap<>();
            body.put("model", "gpt-5.1-codex-mini"); // 사용하시는 모델명 확인 필요
            body.put("input", messages);
            body.put("max_output_tokens", 2000); // 답변이 잘리지 않도록 넉넉하게

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(API_URL, request, Map.class);

            Map<String, Object> responseBody = response.getBody();
            log.info("AI Response Body: {}", responseBody);

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
            // [수정] 에러 발생 시 로그만 남기고 null 반환 (아무 말도 하지 않음)
            log.error("OpenAI API 호출 실패: {}", e.getMessage());
            return null;
        }
    }
}
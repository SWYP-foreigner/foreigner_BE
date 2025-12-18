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
    private static final String API_URL = "https://api.openai.com/v1/responses";

    @Override
    public String generateResponse(List<Map<String, Object>> messages) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> body = new HashMap<>();
            body.put("model", "gpt-5.1-codex-mini");
            body.put("input", messages);

            // [핵심 수정 1] 추론 모델은 '생각'하는 데 토큰을 많이 씁니다.
            // 최소 2000 이상으로 넉넉히 잡아야 실제 답변이 출력됩니다.
            body.put("max_output_tokens", 2000);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(API_URL, request, Map.class);

            Map<String, Object> responseBody = response.getBody();
            log.info("AI Response Body: {}", responseBody);

            if (responseBody != null && responseBody.containsKey("output")) {
                List<Map<String, Object>> outputs = (List<Map<String, Object>>) responseBody.get("output");

                // [핵심 수정 2] output 배열을 순회하며 type이 'text'인 블록의 content를 찾습니다.
                for (Map<String, Object> out : outputs) {
                    if ("text".equals(out.get("type")) && out.containsKey("content")) {
                        return (String) out.get("content");
                    }
                }

                // 만약 아직 답변이 안 나왔다면 상태 확인
                if ("incomplete".equals(responseBody.get("status"))) {
                    log.warn("AI가 추론 중에 끊겼습니다. 토큰을 더 늘려보세요.");
                    return "아... 생각하다 까먹었어. 다시 말해줄래? ㅡㅡ";
                }
            }

            throw new RuntimeException("Unexpected response structure or empty content");

        } catch (Exception e) {
            log.error("OpenAI v1/responses 호출 오류: {}", e.getMessage());
            return "아 서버 진짜 개판이네 ㅋㅋㅋ 나중에 다시 해";
        }
    }
}
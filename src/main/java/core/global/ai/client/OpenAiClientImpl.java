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

            // [핵심 수정] 에러 메시지에 따라 'messages'를 'input'으로 변경합니다.
            body.put("input", messages);

            body.put("temperature", 0.8);
            body.put("max_tokens", 150);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(API_URL, request, Map.class);

            Map<String, Object> responseBody = response.getBody();
            log.info("AI Response Body: {}", responseBody); // 구조 확인을 위한 로그

            // [수정] Responses API 전용 파싱 로직
            if (responseBody != null) {
                // v1/responses는 보통 'output' 배열이나 객체 내부에 데이터를 담습니다.
                if (responseBody.containsKey("output")) {
                    Object outputObj = responseBody.get("output");

                    // 리스트 형태인 경우 (최신 규격)
                    if (outputObj instanceof List) {
                        List<Map<String, Object>> outputList = (List<Map<String, Object>>) outputObj;
                        if (!outputList.isEmpty()) {
                            Map<String, Object> lastMsg = (Map<String, Object>) outputList.get(outputList.size() - 1);
                            // 'content' 필드 추출 (구조에 따라 'message' 내부일 수 있음)
                            if (lastMsg.containsKey("content")) return (String) lastMsg.get("content");
                        }
                    }
                    // 단일 객체 형태인 경우
                    else if (outputObj instanceof Map) {
                        Map<String, Object> outputMap = (Map<String, Object>) outputObj;
                        return (String) outputMap.get("content");
                    }
                }
            }

            throw new RuntimeException("Unexpected response structure from Responses API");

        } catch (Exception e) {
            log.error("OpenAI v1/responses 호출 오류: {}", e.getMessage());
            return "";
        }
    }
}
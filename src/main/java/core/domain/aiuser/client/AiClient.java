package core.domain.aiuser.client;

import java.util.List;
import java.util.Map;

public interface AiClient {
    /**
     * AI 모델에게 메시지 리스트(시스템 프롬프트 + 대화 내역)를 보내고 답변을 받습니다.
     * @param messages OpenAI Chat Completion API 형식의 메시지 리스트
     * @return AI의 응답 텍스트
     */
    String generateResponse(List<Map<String, Object>> messages);
}